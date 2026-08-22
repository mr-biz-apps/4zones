package uk.mr_biz.fourzones.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import uk.mr_biz.fourzones.BuildConfig
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * [PrivilegedBackend] implemented with Shizuku (current API level, v11+
 * permission flow, UserService for privileged work — no deprecated process
 * APIs, no root).
 *
 * Framework wrapper kept thin: readiness FACTS go through the pure
 * [PrivilegedBackendStatusResolver]; the connection LIFECYCLE (bind →
 * handshake → verify → replace-once → fail closed, across arbitrary async
 * callback orderings) goes through the framework-free
 * [TopologyConnectionCoordinator]; and all privileged output is filtered in the
 * user-service process before reaching this one.
 *
 * A connected candidate binder is never usable until its compiled protocol
 * version is verified (see [TopologyProtocol]); READY is reported only for a
 * verified, current-generation, alive service. This closes the stale-service
 * hazard where an old `:topology` process survived an `install -r`.
 *
 * Threading: public methods and every Shizuku/service callback run on the main
 * thread; the blocking binder calls (handshake, read, resize) run on worker
 * threads and hop their results back to the main thread, where the coordinator
 * validates them against the current generation.
 */
class ShizukuPrivilegedBackend(context: Context) : PrivilegedBackend, TaskResizeGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var onStatusChanged: ((PrivilegedBackendStatus) -> Unit)? = null

    private var binderEverDied = false
    private var permissionDeniedByUser = false

    // One ServiceConnection per bind generation, so every connect/disconnect
    // callback is unambiguously attributable to its generation (Shizuku's
    // onServiceDisconnected carries no binder identity). A delayed disconnect
    // from a replaced generation is therefore recognizable as obsolete.
    private val connections = mutableMapOf<Long, ServiceConnection>()

    private val effects: TopologyConnectionCoordinator.Effects<IDesktopTopologyShellService> =
        object : TopologyConnectionCoordinator.Effects<IDesktopTopologyShellService> {
        override fun bind(generation: Long) {
            val connection = newConnection(generation)
            connections[generation] = connection
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (e: Exception) {
                connections.remove(generation)
                Log.w(TAG, "bind failed for generation $generation: ${e.javaClass.simpleName}")
                // Defer so the coordinator is not re-entered mid-transition.
                mainHandler.post { coordinator.onDisconnected(generation) }
            }
        }

        override fun removeStale(generation: Long) {
            // remove=true asks the Shizuku server to DESTROY the stale service
            // record so the replacement bind must create a fresh process. The
            // disconnect this may trigger belongs to the (now obsolete) stale
            // generation and is ignored by the coordinator.
            connections[generation]?.let {
                runCatching { Shizuku.unbindUserService(userServiceArgs, it, true) }
            }
        }

        override fun startHandshake(generation: Long, candidate: IDesktopTopologyShellService) {
            val worker = Runnable {
                val reported = try {
                    candidate.protocolVersion()
                } catch (e: Exception) {
                    // A service predating protocolVersion() cannot answer.
                    null
                }
                mainHandler.post { coordinator.onHandshakeResult(generation, candidate, reported) }
            }
            Thread(worker, "dexzones-topology-handshake").start()
        }

        override fun executeRead(
            readId: Long,
            generation: Long,
            service: IDesktopTopologyShellService,
        ) {
            val worker = Runnable {
                val result = try {
                    TopologyReadResult.Success(service.readActivityTopology())
                } catch (e: Exception) {
                    TopologyReadResult.CommandFailed(
                        "Privileged topology read failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
                // The coordinator — not the worker — decides delivery, so a
                // result from a generation that has since died is never handed to
                // the consumer (and never reaches T1/T2).
                mainHandler.post { coordinator.onReadCompleted(readId, generation, service, result) }
            }
            Thread(worker, "dexzones-topology-read").start()
        }

        override fun onStatusChanged() = publishStatus()
    }

    private val coordinator: TopologyConnectionCoordinator<IDesktopTopologyShellService> =
        TopologyConnectionCoordinator(
            expectedVersion = TopologyProtocol.VERSION,
            isAlive = { service ->
                runCatching { service.asBinder().isBinderAlive }.getOrDefault(false)
            },
            effects = effects,
        )

    // The global Shizuku listeners are recreated per start() session and stamped
    // with that session's token so a callback queued in a previous session can
    // never act on a later one (see [BackendSessionGate]). Held here so stop()
    // can unregister exactly the current session's listeners (no accumulation).
    private val sessionGate = BackendSessionGate()
    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
    private var permissionResultListener: Shizuku.OnRequestPermissionResultListener? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, DesktopTopologyShellService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("topology")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private fun newConnection(generation: Long) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                coordinator.onDisconnected(generation)
                return
            }
            // Hand the candidate to the coordinator; it is NOT usable until the
            // coordinator verifies its protocol version.
            coordinator.onConnected(
                generation,
                IDesktopTopologyShellService.Stub.asInterface(binder),
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            connections.remove(generation)
            // Only the coordinator decides whether this matters (obsolete if it
            // is not the current generation, e.g. a delayed remove=true callback).
            coordinator.onDisconnected(generation)
        }
    }

    override fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit) {
        requireMainThread("start")
        if (started) return
        started = true
        this.onStatusChanged = onStatusChanged
        // Open a fresh lifecycle session; listeners registered below are stamped
        // with THIS token, so a delayed callback from a prior session is inert.
        val session = sessionGate.open()
        // Reactivate the coordinator on a fresh session/generation (this same
        // backend may be restarted after an Activity stop/start cycle).
        coordinator.start()
        val received = Shizuku.OnBinderReceivedListener {
            if (sessionGate.isActive(session)) {
                publishStatus()
                ensureConnectingIfReady()
            }
        }
        val dead = Shizuku.OnBinderDeadListener {
            if (sessionGate.isActive(session)) {
                binderEverDied = true
                // The Shizuku binder itself died: void the connection generation,
                // fail owned reads once, republish status (via onStatusChanged).
                coordinator.onBinderDied()
            }
        }
        val permission = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (sessionGate.isActive(session)) {
                permissionDeniedByUser = grantResult != PackageManager.PERMISSION_GRANTED
                publishStatus()
                ensureConnectingIfReady()
            }
        }
        binderReceivedListener = received
        binderDeadListener = dead
        permissionResultListener = permission
        // Sticky variant: fires immediately if the binder is already here.
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        publishStatus()
        // Verify eagerly so READY becomes reachable without a prior read (the
        // UI gates its actions on READY, which now requires verification).
        ensureConnectingIfReady()
    }

    override fun stop() {
        requireMainThread("stop")
        if (!started) return
        started = false
        onStatusChanged = null
        // Invalidate the lifecycle session FIRST so any already-queued global
        // listener callback (e.g. a binder-death notification) sees itself as
        // stale even if it runs during this teardown.
        sessionGate.close()
        // Invalidate the connection generation, fail owned reads once, make late
        // callbacks inert.
        coordinator.stop()
        // Unregister exactly this session's listeners (no accumulation).
        binderReceivedListener?.let { Shizuku.removeBinderReceivedListener(it) }
        binderDeadListener?.let { Shizuku.removeBinderDeadListener(it) }
        permissionResultListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
        binderReceivedListener = null
        binderDeadListener = null
        permissionResultListener = null
        // Release every outstanding connection (remove=false: do not ask the
        // server to destroy the shared service record).
        connections.values.forEach {
            runCatching { Shizuku.unbindUserService(userServiceArgs, it, false) }
        }
        connections.clear()
    }

    override fun requestPermission() {
        requireMainThread("requestPermission")
        val base = resolveBaseStatus()
        if (base != PrivilegedBackendStatus.PERMISSION_REQUIRED &&
            base != PrivilegedBackendStatus.PERMISSION_DENIED
        ) {
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { publishStatus() }
    }

    override fun readActivityTopology(onResult: (TopologyReadResult) -> Unit) {
        requireMainThread("readActivityTopology")
        val base = resolveBaseStatus()
        if (base != PrivilegedBackendStatus.READY) {
            // Not permission/binder-ready yet: fail with the underlying reason.
            onResult(TopologyReadResult.BackendUnavailable(base))
            return
        }
        // Permission/binder are ready; the coordinator owns verification,
        // queuing, mismatch fail-closed, and exactly-once completion.
        coordinator.requestRead(onResult)
    }

    /**
     * NON-QUEUING topology read for physical-shortcut mutating transactions:
     * "use the current verified privileged service NOW, or fail". Unlike
     * [readActivityTopology], it never permits acquisition/queueing — if no
     * current, protocol-verified, alive service exists at admission it returns
     * [TopologyReadResult.BackendUnavailable] immediately and is NEVER resumed
     * after reconnect. Same fixed remote `readActivityTopology` call, same
     * filter/parser; only admission semantics differ (see
     * [TopologyConnectionCoordinator.requestReadNow]). This method starts no
     * bind/rebind of its own.
     */
    fun readActivityTopologyNow(onResult: (TopologyReadResult) -> Unit) {
        requireMainThread("readActivityTopologyNow")
        val base = resolveBaseStatus()
        if (base != PrivilegedBackendStatus.READY) {
            onResult(TopologyReadResult.BackendUnavailable(base))
            return
        }
        coordinator.requestReadNow(onResult)
    }

    // ---------------------------------------------------------- mutation path

    /**
     * The single mutating operation. Integrates with the SAME Shizuku
     * readiness/verification used by reads.
     *
     * A mutation is NEVER deferred across binding or attempted against an
     * unverified candidate: it requires a currently VERIFIED, current-generation,
     * alive user service at the call moment (see
     * [TopologyConnectionCoordinator.verifiedServiceOrNull]); in every other
     * state (disconnected, binding, handshaking, replacing, version mismatch,
     * binder dead) it fails closed with [TaskResizeOutcome.BackendUnavailable]
     * and the AIDL resize method is never invoked.
     */
    override fun resizeTask(
        taskId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        onResult: (TaskResizeOutcome) -> Unit,
    ) {
        requireMainThread("resizeTask")
        val base = resolveBaseStatus()
        if (base != PrivilegedBackendStatus.READY) {
            onResult(TaskResizeOutcome.BackendUnavailable(base))
            return
        }
        if (coordinator.versionMismatch != null) {
            onResult(
                TaskResizeOutcome.BackendUnavailable(
                    PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
                ),
            )
            return
        }
        if (right <= left || bottom <= top) {
            onResult(TaskResizeOutcome.Rejected("inverted or empty destination bounds"))
            return
        }
        val service = coordinator.verifiedServiceOrNull()
        if (service == null) {
            // Connected-but-unverified (CONNECTING) or otherwise not usable.
            onResult(TaskResizeOutcome.BackendUnavailable(currentStatus()))
            return
        }
        executeResize(service, taskId, left, top, right, bottom, onResult)
    }

    private fun executeResize(
        service: IDesktopTopologyShellService,
        taskId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        onResult: (TaskResizeOutcome) -> Unit,
    ) {
        val worker = Runnable {
            val outcome = try {
                mapResizeStatus(service.resizeTask(taskId, left, top, right, bottom))
            } catch (e: Exception) {
                TaskResizeOutcome.ProcessError
            }
            mainHandler.post { onResult(outcome) }
        }
        Thread(worker, "dexzones-task-resize").start()
    }

    private fun mapResizeStatus(code: Int): TaskResizeOutcome = when (code) {
        TaskResizeCommand.STATUS_SUCCESS -> TaskResizeOutcome.CommandSucceeded
        TaskResizeCommand.STATUS_COMMAND_FAILED -> TaskResizeOutcome.CommandFailed
        TaskResizeCommand.STATUS_TIMED_OUT -> TaskResizeOutcome.TimedOut
        TaskResizeCommand.STATUS_REJECTED ->
            TaskResizeOutcome.Rejected("privileged service rejected the bounds")
        else -> TaskResizeOutcome.ProcessError
    }

    // --------------------------------------------------------------- status

    /** Kicks off eager verification when permission/binder are ready and idle. */
    private fun ensureConnectingIfReady() {
        if (!started) return
        if (resolveBaseStatus() == PrivilegedBackendStatus.READY) {
            coordinator.beginAcquisitionIfIdle()
        }
    }

    /**
     * The observable backend status. A LIVE Shizuku prerequisite (not installed,
     * binder unavailable/dead, unsupported, permission required/denied) always
     * wins — a stale recorded mismatch can never hide a current permission or
     * binder condition. Only when the base is READY-capable does coordinator
     * state decide: mismatch, verified (READY), or still-connecting.
     *
     * Read-only observational accessor (public so a caller can gate a mutation
     * on `== READY`, i.e. a current, protocol-verified, alive privileged service
     * per the existing lifecycle/version-safety model). It performs no state
     * transition and is the SAME truth this backend uses to permit reads/resize.
     * Main-thread only, like the rest of this class.
     */
    fun currentStatus(): PrivilegedBackendStatus =
        TopologyConnectionCoordinator.effectiveStatus(
            base = resolveBaseStatus(),
            versionMismatch = coordinator.versionMismatch != null,
            verified = coordinator.isVerified,
        )

    /** Pure Shizuku-fact status (permission/binder/server), never verification. */
    private fun resolveBaseStatus(): PrivilegedBackendStatus {
        val binderAlive = Shizuku.pingBinder()
        // Pre-v11 servers lack the permission flow this implementation uses;
        // they resolve to UNSUPPORTED_SERVER, never to a permission state.
        val serverSupported = binderAlive &&
            runCatching { !Shizuku.isPreV11() }.getOrDefault(false)
        val permissionGranted = serverSupported &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        val deniedByUser = permissionDeniedByUser ||
            (serverSupported &&
                runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false))
        return PrivilegedBackendStatusResolver.resolve(
            managerInstalled = isManagerInstalled(),
            binderAlive = binderAlive,
            binderEverDied = binderEverDied,
            serverSupported = serverSupported,
            permissionGranted = permissionGranted,
            permissionDeniedByUser = deniedByUser,
        )
    }

    private fun isManagerInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0)
        true
    }.getOrDefault(false)

    private fun publishStatus() {
        if (!started) return
        onStatusChanged?.invoke(currentStatus())
    }

    private fun requireMainThread(method: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ShizukuPrivilegedBackend.$method() must be called on the main thread"
        }
    }

    private companion object {
        const val TAG = "DexZonesShizuku"
        const val PERMISSION_REQUEST_CODE = 4201
    }
}

package uk.mr_biz.fourzones.privileged

/**
 * A privileged-boundary protocol mismatch, surfaced verbatim in diagnostics as
 * `expected=<N> actual=<M>`. [actual] is null when the connected UserService
 * could not report a version at all (an older service predating
 * [IDesktopTopologyShellService.protocolVersion]).
 */
data class UserServiceVersionMismatch(val expected: Int, val actual: Int?)

/**
 * Framework-free, generation-aware lifecycle coordinator for the privileged
 * topology UserService connection.
 *
 * The connection is driven entirely by asynchronous callbacks (bind →
 * onServiceConnected → an off-thread `protocolVersion()` handshake → off-thread
 * reads → onServiceDisconnected / binder death), any of which may arrive out of
 * order or after the candidate/generation they refer to has been superseded —
 * most sharply when Shizuku hands back a STALE service that survived an
 * `install -r`. A monotonic [generation] token makes those orderings safe: every
 * asynchronous result carries the generation (and, for handshakes and reads, the
 * service identity) it was issued for, and is acted on only while still current.
 *
 * Two central invariants:
 *  - a candidate binder is NOT usable merely because onServiceConnected fired;
 *    only a [State.Verified] of the current generation with a live service is
 *    ever handed to a read or resize;
 *  - consumer completion belongs to COORDINATOR STATE, never directly to an
 *    asynchronous worker: a dispatched read is owned by generation until the
 *    coordinator itself validates and delivers (or invalidates) it, so a Success
 *    produced by a generation that has since died can never reach the consumer.
 *
 * Lifecycle: [start]/[stop] are a reusable session pair (an Activity may stop
 * then start the same backend). [stop] deactivates the session and invalidates
 * its generation; [start] begins a FRESH session on a NEW generation (numbers
 * only ever increase — callbacks from a prior session stay obsolete forever).
 * There is no permanent disposal.
 *
 * All methods run on one serialized thread (the wrapper's main thread); the
 * coordinator performs no IPC — it invokes [Effects] the wrapper implements
 * against Shizuku and worker threads, and worker results are posted back onto
 * this same thread before any consumer callback runs.
 *
 * @param S the privileged-service handle type (the AIDL interface in
 * production, a fake in tests). Identity is compared with `===`.
 */
class TopologyConnectionCoordinator<S : Any>(
    private val expectedVersion: Int,
    private val isAlive: (S) -> Boolean,
    private val effects: Effects<S>,
) {

    /** Side effects the framework wrapper performs on the coordinator's behalf. */
    interface Effects<S : Any> {
        /** Create a connection stamped with [generation] and bind it. */
        fun bind(generation: Long)

        /** Destroy the stale instance of [generation] (unbind, remove=true). */
        fun removeStale(generation: Long)

        /** Start the off-thread protocol handshake for [candidate] of [generation]. */
        fun startHandshake(generation: Long, candidate: S)

        /**
         * Run a read against [service] for the owned request [readId] of
         * [generation]. The worker MUST report back via [onReadCompleted] — it
         * is never handed the consumer callback directly.
         */
        fun executeRead(readId: Long, generation: Long, service: S)

        /** The observable backend status may have changed. */
        fun onStatusChanged()
    }

    /** Explicit connection lifecycle; only [Verified] is usable by consumers. */
    sealed interface State<out S> {
        data object Disconnected : State<Nothing>
        data class Binding(val generation: Long) : State<Nothing>
        data class Handshaking<out S>(val generation: Long, val candidate: S) : State<S>

        /** The one bounded replacement is binding a fresh instance. */
        data class Replacing(val generation: Long) : State<Nothing>
        data class Verified<out S>(val generation: Long, val service: S) : State<S>

        /** Terminal for this acquisition: the (replaced) service still mismatched. */
        data class VersionMismatch(val mismatch: UserServiceVersionMismatch) : State<Nothing>
    }

    /** Ownership record for a dispatched read, keyed by [readId]. */
    private class InFlightRead<S>(
        val generation: Long,
        val service: S,
        val consumer: (TopologyReadResult) -> Unit,
    )

    private var generation = 0L
    private var state: State<S> = State.Disconnected
    private var active = false

    // The single automatic replacement allowance for the CURRENT acquisition
    // cycle. Reset only when a genuinely new cycle begins from Disconnected —
    // never by a stray/obsolete callback (e.g. the delayed disconnect the
    // remove=true replacement itself causes).
    private var replacementUsedThisCycle = false

    private var nextReadId = 0L
    private val queued = ArrayDeque<(TopologyReadResult) -> Unit>()
    private val inFlight = mutableMapOf<Long, InFlightRead<S>>()

    // ------------------------------------------------------------- lifecycle

    /**
     * Begins (or reactivates) a session on a FRESH generation. Idempotent: a
     * no-op while already active, so it never creates a parallel generation or
     * a duplicate bind. Does not itself bind — the wrapper calls
     * [beginAcquisitionIfIdle] once permission/binder are ready.
     */
    fun start() {
        if (active) return
        ++generation // a new session generation; prior-session callbacks are now obsolete
        state = State.Disconnected
        replacementUsedThisCycle = false
        active = true
    }

    /**
     * Deactivates the session: invalidates its generation, completes every owned
     * read exactly once, and makes all later delayed callbacks inert. Idempotent.
     * NOT permanent — a later [start] reactivates on a new generation.
     */
    fun stop() {
        if (!active) return
        active = false
        ++generation
        state = State.Disconnected
        failAllReads(PrivilegedBackendStatus.BINDER_UNAVAILABLE)
    }

    // --------------------------------------------------------- status queries

    val versionMismatch: UserServiceVersionMismatch?
        get() = (state as? State.VersionMismatch)?.mismatch

    /** True only in [State.Verified] with a still-alive service of the current generation. */
    val isVerified: Boolean get() = verifiedServiceOrNull() != null

    /** The verified, still-alive service, or null in every other state. */
    fun verifiedServiceOrNull(): S? {
        val s = state
        return if (active && s is State.Verified && s.generation == generation && isAlive(s.service)) {
            s.service
        } else {
            null
        }
    }

    // ------------------------------------------------------------- consumers

    /**
     * Queues a read to complete EXACTLY ONCE. Verified → dispatch now (owned by
     * generation until delivery); a connecting/replacing cycle → queued and
     * dispatched on verification (or failed once on disconnect/mismatch);
     * terminal mismatch → fail closed; disconnected → begin a fresh acquisition
     * and queue.
     */
    fun requestRead(onResult: (TopologyReadResult) -> Unit) {
        if (!active) {
            onResult(TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_UNAVAILABLE))
            return
        }
        when (val s = state) {
            is State.Verified ->
                if (s.generation == generation && isAlive(s.service)) {
                    dispatchRead(s.service, onResult)
                } else {
                    invalidateCurrentGeneration(PrivilegedBackendStatus.BINDER_DIED)
                    requestRead(onResult)
                }
            is State.VersionMismatch ->
                onResult(
                    TopologyReadResult.BackendUnavailable(
                        PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
                    ),
                )
            State.Disconnected -> {
                queued.addLast(onResult)
                beginAcquisitionIfIdle()
            }
            is State.Binding, is State.Handshaking, is State.Replacing ->
                queued.addLast(onResult)
        }
    }

    /**
     * NON-QUEUING read admission for callers (e.g. a physical shortcut mutation)
     * that must NOT wait for reacquisition: dispatch immediately against the
     * CURRENT verified, alive service, or fail closed NOW.
     *
     * Distinct from [requestRead] (whose queue-during-acquisition semantics are
     * unchanged for ordinary callers). This operation NEVER enqueues, binds,
     * rebinds, acquires, retains the request, or replays it. If no current live
     * verified service exists, it fails with a typed [TopologyReadResult] and the
     * request is permanently dead — a later reconnect does not resurrect it.
     *
     * When the current [State.Verified] service is no longer alive it invalidates
     * that generation via the existing lifecycle (which may let the backend's own
     * INDEPENDENT policy reacquire later, e.g. on a binder-received event) but it
     * does NOT recurse into [requestRead], does NOT add to the queue, and does
     * NOT reacquire on behalf of THIS request. Once admitted against a live
     * service the read reuses the standard in-flight ownership, so a post-dispatch
     * binder death is failed once and discarded by [onReadCompleted] with no
     * requeue or migration.
     */
    fun requestReadNow(onResult: (TopologyReadResult) -> Unit) {
        if (!active) {
            onResult(TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_UNAVAILABLE))
            return
        }
        when (val s = state) {
            is State.Verified ->
                if (s.generation == generation && isAlive(s.service)) {
                    // Register against the exact current generation/service and
                    // dispatch immediately (standard in-flight ownership).
                    dispatchRead(s.service, onResult)
                } else {
                    // Dead verified service: invalidate the generation per the
                    // existing lifecycle, then fail THIS request. No recursion,
                    // no queue, no acquisition on its behalf.
                    invalidateCurrentGeneration(PrivilegedBackendStatus.BINDER_DIED)
                    onResult(
                        TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED),
                    )
                }
            is State.VersionMismatch ->
                onResult(
                    TopologyReadResult.BackendUnavailable(
                        PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
                    ),
                )
            State.Disconnected ->
                onResult(
                    TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_UNAVAILABLE),
                )
            is State.Binding, is State.Handshaking, is State.Replacing ->
                onResult(TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.CONNECTING))
        }
    }

    /**
     * Ensures a connection is being established when idle. Idempotent: a no-op
     * unless active and [State.Disconnected]. Starts a NEW acquisition cycle,
     * which is the only place the one-shot replacement budget is restored.
     */
    fun beginAcquisitionIfIdle() {
        if (!active || state != State.Disconnected) return
        replacementUsedThisCycle = false
        val gen = ++generation
        state = State.Binding(gen)
        effects.bind(gen)
    }

    /** The verified, current-generation, alive service — or null (resize gate). */
    // (verifiedServiceOrNull above is the resize gate.)

    // --------------------------------------------------- connection callbacks

    /** A bound connection produced a candidate binder for [generation]. */
    fun onConnected(generation: Long, candidate: S) {
        if (!active) return
        val awaitingGen = when (val s = state) {
            is State.Binding -> s.generation
            is State.Replacing -> s.generation
            else -> return // not awaiting a connection: obsolete
        }
        if (generation != awaitingGen || generation != this.generation) return
        state = State.Handshaking(generation, candidate)
        effects.startHandshake(generation, candidate)
    }

    /**
     * The off-thread handshake for [candidate] of [generation] completed with
     * [reported] (null = the service could not report a version → incompatible).
     * Acted on only if still current AND the same candidate AND still alive.
     */
    fun onHandshakeResult(generation: Long, candidate: S, reported: Int?) {
        if (!active) return
        val s = state as? State.Handshaking<S> ?: return
        if (generation != s.generation || candidate !== s.candidate || generation != this.generation) {
            return
        }
        if (!isAlive(candidate)) {
            invalidateCurrentGeneration(PrivilegedBackendStatus.BINDER_DIED)
            return
        }
        when {
            reported == expectedVersion -> {
                state = State.Verified(generation, candidate)
                dispatchQueued(candidate)
                effects.onStatusChanged()
            }
            !replacementUsedThisCycle -> {
                // The single automatic replacement: invalidate this generation,
                // destroy the stale instance, and bind a fresh one. Queued reads
                // are CARRIED through this one bounded replacement.
                replacementUsedThisCycle = true
                val staleGen = generation
                val newGen = ++this.generation
                state = State.Replacing(newGen)
                effects.removeStale(staleGen)
                effects.bind(newGen)
            }
            else -> {
                state = State.VersionMismatch(UserServiceVersionMismatch(expectedVersion, reported))
                failAllReads(PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH)
                effects.onStatusChanged()
            }
        }
    }

    /** A connection for [generation] disconnected. Ignored unless it is current. */
    fun onDisconnected(generation: Long) {
        if (!active) return
        if (generation != this.generation) return // obsolete (e.g. delayed remove=true)
        invalidateCurrentGeneration(PrivilegedBackendStatus.BINDER_DIED)
    }

    /** The Shizuku binder itself died: the whole connection generation is void. */
    fun onBinderDied() {
        if (!active) return
        invalidateCurrentGeneration(PrivilegedBackendStatus.BINDER_DIED)
    }

    // ----------------------------------------------------- read completion

    /**
     * A dispatched read's worker finished. The coordinator — never the worker —
     * decides delivery: the result reaches the consumer ONLY if the request is
     * still owned, its generation is still current, the state is
     * [State.Verified] of that generation, the reporting [service] is still that
     * verified service, AND the service's binder is still alive AT DELIVERY TIME.
     * Callback ordering is the race being fixed, so liveness is re-checked here
     * rather than relying on a (possibly not-yet-processed) death callback.
     *
     * If the still-current verified service is found DEAD here, that is binder
     * death discovered synchronously: the current request is removed first (so
     * generation invalidation cannot also complete it), the generation is
     * invalidated (failing every OTHER owned read once), and this request is
     * then failed once with BINDER_DIED — the stale Success is discarded. Either
     * way the consumer is completed exactly once.
     */
    fun onReadCompleted(readId: Long, generation: Long, service: S, result: TopologyReadResult) {
        val request = inFlight.remove(readId) ?: return // already invalidated/completed
        val s = state
        val currentAndVerified = active &&
            generation == this.generation &&
            request.generation == this.generation &&
            request.service === service &&
            s is State.Verified<*> && s.generation == this.generation && s.service === service
        when {
            !currentAndVerified ->
                // Unreachable in practice (invalidation clears inFlight), but
                // never leave a consumer unresolved: fail closed exactly once.
                request.consumer(
                    TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED),
                )
            !isAlive(service) -> {
                // Binder died between producing the result and delivering it.
                // Invalidate the current generation (fails the OTHER owned reads
                // once; this request is already removed), publish non-ready, then
                // fail this request once. The stale Success is discarded.
                invalidateCurrentGeneration(PrivilegedBackendStatus.BINDER_DIED)
                request.consumer(
                    TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED),
                )
            }
            else -> request.consumer(result)
        }
    }

    // ------------------------------------------------------------- internals

    private fun dispatchRead(service: S, consumer: (TopologyReadResult) -> Unit) {
        val readId = ++nextReadId
        inFlight[readId] = InFlightRead(generation, service, consumer)
        effects.executeRead(readId, generation, service)
    }

    private fun dispatchQueued(service: S) {
        val snapshot = queued.toList()
        queued.clear()
        snapshot.forEach { dispatchRead(service, it) }
    }

    private fun invalidateCurrentGeneration(status: PrivilegedBackendStatus) {
        // Invalidate FIRST so any in-flight callback for the prior generation is
        // recognized as obsolete, drop the candidate/verified reference, then
        // complete every owned read once and go idle. A future read lazily rebinds.
        ++generation
        state = State.Disconnected
        failAllReads(status)
        effects.onStatusChanged()
    }

    private fun failAllReads(status: PrivilegedBackendStatus) {
        val queuedSnapshot = queued.toList()
        queued.clear()
        val inFlightSnapshot = inFlight.values.toList()
        inFlight.clear()
        val failure = TopologyReadResult.BackendUnavailable(status)
        queuedSnapshot.forEach { it(failure) }
        inFlightSnapshot.forEach { it.consumer(failure) }
    }

    companion object {
        /**
         * Pure status precedence: a live Shizuku prerequisite (not-installed,
         * binder unavailable/dead, unsupported server, permission required/denied)
         * ALWAYS wins over recorded coordinator state, so a stale recorded
         * mismatch can never hide a current permission-denied/unavailable. Only
         * when the base is READY-capable does coordinator state decide between
         * mismatch, verified (READY) and connecting.
         */
        fun effectiveStatus(
            base: PrivilegedBackendStatus,
            versionMismatch: Boolean,
            verified: Boolean,
        ): PrivilegedBackendStatus = when {
            base != PrivilegedBackendStatus.READY -> base
            versionMismatch -> PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH
            verified -> PrivilegedBackendStatus.READY
            else -> PrivilegedBackendStatus.CONNECTING
        }
    }
}

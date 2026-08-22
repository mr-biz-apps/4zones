package uk.mr_biz.fourzones.privileged

/**
 * Abstraction over a privileged (shell-identity) execution backend.
 *
 * This interface deliberately exposes only named operations — never an
 * arbitrary command interface — and the operations it declares HERE are
 * read-only. It is NOT the whole privileged surface: an implementation may also
 * implement [TaskResizeGateway], and ShizukuPrivilegedBackend does, so the same
 * object carries the one mutating operation (task resize). UI, workspace and
 * display code must go through these abstractions and can therefore never
 * execute arbitrary shell commands.
 *
 * Threading contract: all methods must be called on the main thread and all
 * callbacks are delivered on the main thread.
 */
interface PrivilegedBackend {

    /**
     * Begins observing backend availability. [onStatusChanged] is invoked
     * with the current status immediately-ish and again on every change
     * (binder arrival, binder death, permission result).
     */
    fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit)

    /** Stops observing and releases any bound privileged service. */
    fun stop()

    /** Asks the backend to request its runtime permission, if applicable. */
    fun requestPermission()

    /**
     * Reads the filtered activity-topology dump through the privileged
     * service. Never throws for unavailability — failures arrive as
     * [TopologyReadResult.BackendUnavailable] or [TopologyReadResult.CommandFailed].
     */
    fun readActivityTopology(onResult: (TopologyReadResult) -> Unit)
}

/**
 * Externally observable backend availability. Every state is a diagnostic
 * outcome to display, never a reason to crash.
 */
enum class PrivilegedBackendStatus {
    /** The Shizuku manager app is not installed. */
    NOT_INSTALLED,

    /** Manager installed, but its service is not running / binder not received. */
    BINDER_UNAVAILABLE,

    /**
     * Binder alive, but the running Shizuku server generation is below the
     * version this implementation supports (pre-v11). Permission cannot be
     * requested and READY is impossible until a supported server is running.
     */
    UNSUPPORTED_SERVER,

    /** Binder alive; permission has not been requested or answered yet. */
    PERMISSION_REQUIRED,

    /** Binder alive; the user denied the permission. */
    PERMISSION_DENIED,

    /**
     * Binder alive and permission granted, but no privileged UserService has
     * been protocol-verified yet (binding or handshaking in progress, including
     * a stale-service replacement). Distinct from READY: a connected candidate
     * is NOT usable until its compiled protocol version is verified. Reads queue
     * until verification; resize fails closed.
     */
    CONNECTING,

    /** Binder alive, permission granted, and a protocol-verified UserService is bound. */
    READY,

    /** The binder was received earlier and has since died. */
    BINDER_DIED,

    /**
     * Bound to a Shizuku UserService whose compiled protocol version does not
     * match this process's expectation — typically an OLD `:topology` service
     * that survived an `install -r` and would feed stale filtered topology to
     * newer parser/resolver code. A single bounded replacement has already been
     * attempted and failed. Fail closed: no topology is consumed and no
     * mutation is attempted until a matching service is bound (restart
     * required). Diagnostics carry the expected/actual versions.
     */
    USER_SERVICE_VERSION_MISMATCH,
}

/** Result of one privileged read operation. */
sealed interface TopologyReadResult {
    /** Filtered structural dump text (never a raw full dump). */
    data class Success(val filteredDump: String) : TopologyReadResult

    /** The backend was not in [PrivilegedBackendStatus.READY]. */
    data class BackendUnavailable(val status: PrivilegedBackendStatus) : TopologyReadResult

    /** The backend was ready but the operation itself failed. */
    data class CommandFailed(val message: String) : TopologyReadResult
}

/**
 * Pure mapping from observed Shizuku facts to a [PrivilegedBackendStatus].
 * Kept framework-free so the state machine is JVM-testable; the framework
 * wrapper only feeds it booleans.
 */
object PrivilegedBackendStatusResolver {

    fun resolve(
        managerInstalled: Boolean,
        binderAlive: Boolean,
        binderEverDied: Boolean,
        serverSupported: Boolean,
        permissionGranted: Boolean,
        permissionDeniedByUser: Boolean,
    ): PrivilegedBackendStatus = when {
        !managerInstalled -> PrivilegedBackendStatus.NOT_INSTALLED
        !binderAlive && binderEverDied -> PrivilegedBackendStatus.BINDER_DIED
        !binderAlive -> PrivilegedBackendStatus.BINDER_UNAVAILABLE
        // An unsupported (pre-v11) server can never reach the permission
        // states or READY: the flow this implementation uses doesn't exist
        // there, and disguising that as "permission required" would invite a
        // permission request that cannot succeed.
        !serverSupported -> PrivilegedBackendStatus.UNSUPPORTED_SERVER
        permissionGranted -> PrivilegedBackendStatus.READY
        permissionDeniedByUser -> PrivilegedBackendStatus.PERMISSION_DENIED
        else -> PrivilegedBackendStatus.PERMISSION_REQUIRED
    }
}

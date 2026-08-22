package uk.mr_biz.fourzones.privileged

/**
 * The single mutating authority in the app. Deliberately narrow: it takes
 * already-validated integer coordinates and performs exactly one task-resize.
 * There is no exec/shell/runCommand method anywhere — the normal app process
 * can never submit arbitrary shell syntax.
 *
 * Coordinates are primitive Ints (converted from a validated GeometryRect at
 * the call site, which is the AIDL-adjacent boundary) so this privileged
 * layer stays decoupled from the geometry layer.
 *
 * Threading: called on the main thread; the callback is delivered on the main
 * thread.
 */
interface TaskResizeGateway {

    fun resizeTask(
        taskId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        onResult: (TaskResizeOutcome) -> Unit,
    )
}

/** Structured outcome of one resize attempt. Only ONE attempt is ever made. */
sealed interface TaskResizeOutcome {

    /** The command exited 0. NOT proof the geometry applied — verify separately. */
    data object CommandSucceeded : TaskResizeOutcome

    /** Backend not READY; no command was launched. */
    data class BackendUnavailable(val status: PrivilegedBackendStatus) : TaskResizeOutcome

    /** Rejected at the boundary (inverted/empty bounds); no command was launched. */
    data class Rejected(val reason: String) : TaskResizeOutcome

    /** The command ran but exited non-zero. */
    data object CommandFailed : TaskResizeOutcome

    /** The command exceeded its timeout and was destroyed. */
    data object TimedOut : TaskResizeOutcome

    /** The process could not be run/reaped cleanly. */
    data object ProcessError : TaskResizeOutcome
}

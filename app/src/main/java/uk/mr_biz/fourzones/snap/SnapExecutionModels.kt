package uk.mr_biz.fourzones.snap

import uk.mr_biz.fourzones.desktop.DesktopTopologySnapshot
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.GeometryRect
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus

/**
 * Phase 2C3A read/mutate boundary note: everything up to and including
 * precondition revalidation is READ-ONLY. The ONLY mutation in the whole app
 * is [uk.mr_biz.fourzones.privileged.TaskResizeGateway.resizeTask], invoked once
 * by [SnapExecutionOrchestrator] after every precondition has passed.
 */

/** Source of a fresh topology reading. Production wraps the privileged backend. */
fun interface TopologySource {
    fun fetch(onResult: (TopologyFetch) -> Unit)
}

sealed interface TopologyFetch {
    data class Fetched(val snapshot: DesktopTopologySnapshot) : TopologyFetch
    data class Unavailable(val status: PrivilegedBackendStatus) : TopologyFetch
    data class Failed(val message: String) : TopologyFetch
}

/** Source of fresh per-display Phase 2C2 geometry. Production wraps DisplayGeometryReader. */
fun interface DisplayGeometrySource {
    fun read(displayId: Int): DesktopWorkAreaAssessment
}

/**
 * Structured outcome of one snap execution. There is exactly one mutation
 * attempt; success requires the observed OUTER task bounds to equal the
 * requested rectangle exactly (Samsung/app policy altering the bounds is a
 * [PostconditionMismatch], never a success).
 *
 * Ephemeral diagnostic fields (task ID, package/component) are for display
 * only — never persisted, never treated as identity, never a constant.
 */
sealed interface SnapExecutionResult {

    val quadrant: Quadrant

    data class AppliedAndVerified(
        override val quadrant: Quadrant,
        val displayId: Int,
        val taskId: Int,
        val packageName: String?,
        val componentName: String?,
        val bounds: GeometryRect,
    ) : SnapExecutionResult

    data class NoTarget(override val quadrant: Quadrant, val reason: String) : SnapExecutionResult

    /**
     * The execution was superseded (replaced by a newer request) or the
     * controller stopped BEFORE the mutation boundary; no mutation occurred.
     */
    data class Cancelled(override val quadrant: Quadrant) : SnapExecutionResult

    data class GeometryUnavailable(
        override val quadrant: Quadrant,
        val reason: String,
    ) : SnapExecutionResult

    data class PreconditionChanged(
        override val quadrant: Quadrant,
        val reason: String,
    ) : SnapExecutionResult

    data class PrivilegeUnavailable(
        override val quadrant: Quadrant,
        val status: PrivilegedBackendStatus,
    ) : SnapExecutionResult

    data class TopologyUnavailable(
        override val quadrant: Quadrant,
        val reason: String,
    ) : SnapExecutionResult

    data class InvalidDestination(
        override val quadrant: Quadrant,
        val reason: String,
    ) : SnapExecutionResult

    data class CommandFailed(override val quadrant: Quadrant, val reason: String) :
        SnapExecutionResult

    data class CommandTimedOut(override val quadrant: Quadrant) : SnapExecutionResult

    data class PostconditionUnavailable(
        override val quadrant: Quadrant,
        val reason: String,
    ) : SnapExecutionResult

    data class PostconditionMismatch(
        override val quadrant: Quadrant,
        val displayId: Int,
        val taskId: Int,
        val requested: GeometryRect,
        /** Null when the task vanished, was ambiguous, or membership was wrong. */
        val observed: GeometryRect?,
        /** Why verification failed (bounds differ, membership changed, absent, ...). */
        val reason: String,
    ) : SnapExecutionResult
}

/** Controller-visible lifecycle state for the diagnostic UI. */
sealed interface SnapExecutionState {
    data object Idle : SnapExecutionState
    data class Pending(val quadrant: Quadrant) : SnapExecutionState
    data class Executing(val quadrant: Quadrant) : SnapExecutionState
    data class Completed(val result: SnapExecutionResult) : SnapExecutionState
}

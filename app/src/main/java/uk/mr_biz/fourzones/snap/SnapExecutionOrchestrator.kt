package uk.mr_biz.fourzones.snap

import uk.mr_biz.fourzones.desktop.ActiveDesktopAssessment
import uk.mr_biz.fourzones.desktop.DesktopTopologySnapshot
import uk.mr_biz.fourzones.desktop.SnapTargetAssessment
import uk.mr_biz.fourzones.desktop.SnapTargetResolver
import uk.mr_biz.fourzones.geometry.DesktopWorkAreaAssessment
import uk.mr_biz.fourzones.geometry.GeometryRect
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.TaskResizeGateway
import uk.mr_biz.fourzones.privileged.TaskResizeOutcome

/**
 * Orchestrates ONE snap execution at TIMER-FIRE time (never at button press).
 * This is the product's main feature path, not a diagnostic: the keyboard
 * shortcut runs through it. It performs, in order: a fresh topology read, target
 * resolution via the unchanged [SnapTargetResolver], a fresh per-display
 * geometry read, a SECOND fresh topology read for TOCTOU revalidation, the
 * single privileged resize, and a THIRD fresh read for postcondition
 * verification.
 *
 * Correctness over latency: if the resolved target changes at all between the
 * two pre-mutation reads, no mutation is attempted. No stored Phase 2C1
 * capture result is ever reused; every read is fresh and the target is
 * re-resolved through the approved rules (active desktop, structural
 * membership, self/launcher/cross-desktop exclusion, ambiguity => no target).
 *
 * The ONLY mutation is [gateway]. If any precondition fails, [gateway] is
 * never reached.
 *
 * All callbacks run on the main thread (the sources and gateway guarantee it).
 */
class SnapExecutionOrchestrator(
    private val topologySource: TopologySource,
    private val geometrySource: DisplayGeometrySource,
    private val gateway: TaskResizeGateway,
    private val selfPackageName: String,
) {

    /** Immutable identity of a resolved target within a single execution. */
    private data class ResolvedTarget(
        val displayId: Int,
        val taskId: Int,
        val packageName: String?,
        val componentName: String?,
    )

    /**
     * @param isCancelled checked immediately BEFORE the single mutation. If a
     * newer request superseded this one, or the controller stopped, the
     * mutation is skipped entirely (never merely suppressed after the fact).
     */
    fun execute(
        quadrant: Quadrant,
        isCancelled: () -> Boolean = { false },
        onResult: (SnapExecutionResult) -> Unit,
    ) {
        // Read 1.
        topologySource.fetch { fetch1 ->
            when (fetch1) {
                is TopologyFetch.Unavailable ->
                    onResult(SnapExecutionResult.PrivilegeUnavailable(quadrant, fetch1.status))
                is TopologyFetch.Failed ->
                    onResult(SnapExecutionResult.TopologyUnavailable(quadrant, fetch1.message))
                is TopologyFetch.Fetched ->
                    afterFirstRead(quadrant, isCancelled, fetch1.snapshot, onResult)
            }
        }
    }

    private fun afterFirstRead(
        quadrant: Quadrant,
        isCancelled: () -> Boolean,
        snapshot1: DesktopTopologySnapshot,
        onResult: (SnapExecutionResult) -> Unit,
    ) {
        val target1 = resolveSingleTarget(snapshot1)
            ?: return onResult(
                SnapExecutionResult.NoTarget(quadrant, noTargetReason(snapshot1)),
            )

        // Fresh geometry for THAT target's display.
        val geometry = geometrySource.read(target1.displayId)
        if (geometry !is DesktopWorkAreaAssessment.Found) {
            return onResult(
                SnapExecutionResult.GeometryUnavailable(quadrant, describeGeometry(geometry)),
            )
        }
        val destination = geometry.destinationQuadrants[quadrant]
            ?: return onResult(
                SnapExecutionResult.GeometryUnavailable(
                    quadrant,
                    "requested quadrant is not present in the geometry result",
                ),
            )
        // Fail closed again at the mutation boundary.
        if (destination.right <= destination.left || destination.bottom <= destination.top) {
            return onResult(
                SnapExecutionResult.InvalidDestination(quadrant, "destination bounds are not positive"),
            )
        }

        // Read 2 — TOCTOU revalidation.
        topologySource.fetch { fetch2 ->
            when (fetch2) {
                is TopologyFetch.Unavailable ->
                    onResult(SnapExecutionResult.PrivilegeUnavailable(quadrant, fetch2.status))
                is TopologyFetch.Failed ->
                    onResult(
                        SnapExecutionResult.PreconditionChanged(
                            quadrant,
                            "topology unavailable during revalidation: ${fetch2.message}",
                        ),
                    )
                is TopologyFetch.Fetched ->
                    afterRevalidation(quadrant, isCancelled, target1, destination, fetch2.snapshot, onResult)
            }
        }
    }

    private fun afterRevalidation(
        quadrant: Quadrant,
        isCancelled: () -> Boolean,
        target1: ResolvedTarget,
        destination: GeometryRect,
        snapshot2: DesktopTopologySnapshot,
        onResult: (SnapExecutionResult) -> Unit,
    ) {
        val target2 = resolveSingleTarget(snapshot2)
        if (target2 == null || target2 != target1) {
            return onResult(
                SnapExecutionResult.PreconditionChanged(
                    quadrant,
                    "resolved target changed between the two fresh reads; no mutation",
                ),
            )
        }

        // Final cancellation boundary: a replacement/stop after this point can
        // only suppress publication, so the check must be HERE, immediately
        // before the mutation. Superseded or stopped => no mutation.
        if (isCancelled()) {
            return onResult(SnapExecutionResult.Cancelled(quadrant))
        }

        // Single mutation attempt.
        gateway.resizeTask(
            target1.taskId,
            destination.left,
            destination.top,
            destination.right,
            destination.bottom,
        ) { outcome ->
            when (outcome) {
                is TaskResizeOutcome.BackendUnavailable ->
                    onResult(SnapExecutionResult.PrivilegeUnavailable(quadrant, outcome.status))
                is TaskResizeOutcome.Rejected ->
                    onResult(SnapExecutionResult.InvalidDestination(quadrant, outcome.reason))
                TaskResizeOutcome.CommandFailed ->
                    onResult(SnapExecutionResult.CommandFailed(quadrant, "resize command failed"))
                TaskResizeOutcome.TimedOut ->
                    onResult(SnapExecutionResult.CommandTimedOut(quadrant))
                TaskResizeOutcome.ProcessError ->
                    onResult(SnapExecutionResult.CommandFailed(quadrant, "resize process error"))
                TaskResizeOutcome.CommandSucceeded ->
                    verifyPostcondition(quadrant, target1, destination, onResult)
            }
        }
    }

    private fun verifyPostcondition(
        quadrant: Quadrant,
        target: ResolvedTarget,
        destination: GeometryRect,
        onResult: (SnapExecutionResult) -> Unit,
    ) {
        // Read 3 — postcondition. Exit code 0 is NOT proof; the geometry must
        // actually be present on the task.
        topologySource.fetch { fetch3 ->
            when (fetch3) {
                is TopologyFetch.Unavailable ->
                    onResult(
                        SnapExecutionResult.PostconditionUnavailable(
                            quadrant,
                            "privilege unavailable during verification: ${fetch3.status}",
                        ),
                    )
                is TopologyFetch.Failed ->
                    onResult(
                        SnapExecutionResult.PostconditionUnavailable(quadrant, fetch3.message),
                    )
                is TopologyFetch.Fetched ->
                    checkObservedBounds(quadrant, target, destination, fetch3.snapshot, onResult)
            }
        }
    }

    private fun checkObservedBounds(
        quadrant: Quadrant,
        target: ResolvedTarget,
        destination: GeometryRect,
        snapshot3: DesktopTopologySnapshot,
        onResult: (SnapExecutionResult) -> Unit,
    ) {
        fun mismatch(observed: GeometryRect?, reason: String) = onResult(
            SnapExecutionResult.PostconditionMismatch(
                quadrant, target.displayId, target.taskId, destination, observed, reason,
            ),
        )

        // Structural lookup: EVERY (root, task) pair whose task matches by ID,
        // across the whole parsed hierarchy (never task-ID proximity). A unique
        // match is required — duplicate IDs from malformed parsing must not
        // arbitrarily verify.
        val matches = snapshot3.roots.flatMap { root ->
            root.childTasks.filter { it.taskId == target.taskId }.map { root to it }
        }
        val match = matches.singleOrNull()
            ?: return mismatch(null, "target task absent or ambiguous after resize")
        val (ownerRoot, task) = match
        val observedRect = task.bounds?.let { GeometryRect(it.left, it.top, it.right, it.bottom) }

        // Exact display equality (a null owner display fails this too).
        if (ownerRoot.displayId != target.displayId) {
            return mismatch(observedRect, "target moved to a different display")
        }
        // The owner root must be the ACTIVE logical desktop on that display —
        // a matching task on an inactive desktop (or None/Ambiguous/Unsupported)
        // must never verify.
        val active = snapshot3.activeDesktopByDisplay[target.displayId]
        if (active !is ActiveDesktopAssessment.Found || active.rootTaskId != ownerRoot.rootTaskId) {
            return mismatch(observedRect, "target no longer in the active desktop")
        }
        if (observedRect == null) {
            return onResult(
                SnapExecutionResult.PostconditionUnavailable(
                    quadrant, "observed task bounds unavailable after resize",
                ),
            )
        }
        return if (observedRect == destination) {
            onResult(
                SnapExecutionResult.AppliedAndVerified(
                    quadrant = quadrant,
                    displayId = target.displayId,
                    taskId = target.taskId,
                    packageName = target.packageName,
                    componentName = target.componentName,
                    bounds = destination,
                ),
            )
        } else {
            mismatch(observedRect, "observed bounds differ from requested")
        }
    }

    /**
     * Resolves the single safe target across all displays via the unchanged
     * resolver. Returns null when there is not exactly one Found (covers self,
     * launcher, no active desktop, ambiguity, cross-desktop, no focus — all of
     * which yield a non-Found assessment).
     */
    private fun resolveSingleTarget(snapshot: DesktopTopologySnapshot): ResolvedTarget? {
        val founds = SnapTargetResolver.resolve(snapshot, selfPackageName)
            .values
            .filterIsInstance<SnapTargetAssessment.Found>()
        val found = founds.singleOrNull() ?: return null
        return ResolvedTarget(
            displayId = found.displayId,
            taskId = found.targetTaskId,
            packageName = found.packageName,
            componentName = found.componentName,
        )
    }

    private fun noTargetReason(snapshot: DesktopTopologySnapshot): String {
        val reasons = SnapTargetResolver.resolve(snapshot, selfPackageName).values
        return reasons.filterIsInstance<SnapTargetAssessment.NoTarget>().firstOrNull()?.reason
            ?: reasons.filterIsInstance<SnapTargetAssessment.Ambiguous>().firstOrNull()?.reason
            ?: reasons.filterIsInstance<SnapTargetAssessment.Unsupported>().firstOrNull()?.reason
            ?: "no safe snap target in the active desktop"
    }

    private fun describeGeometry(assessment: DesktopWorkAreaAssessment): String =
        when (assessment) {
            is DesktopWorkAreaAssessment.Invalid -> "geometry invalid: ${assessment.reason}"
            is DesktopWorkAreaAssessment.Unsupported -> "geometry unsupported: ${assessment.reason}"
            is DesktopWorkAreaAssessment.Found -> "geometry found" // unreachable in this path
        }
}

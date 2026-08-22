package uk.mr_biz.fourzones.desktop

/**
 * Read-only assessment of whether the focused application task is a safe
 * snap target within a positively-resolved active desktop. Computing it mutates
 * nothing — but the result IS acted on: SnapExecutionOrchestrator makes a
 * positive assessment the precondition of the single privileged task resize,
 * and aborts before any mutation when the assessment is not positive.
 *
 * Ordering rule this model encodes: the active desktop is resolved FIRST
 * (Phase 2B, from desk-root type + hierarchy + visibility — never focus);
 * focus is used only afterwards, and only to identify the intended
 * application task WITHIN that already-resolved hierarchy. Focus is never
 * evidence of active-desktop identity.
 *
 * Task and root IDs carried here are session-local opaque handles from one
 * topology snapshot; they identify nothing beyond that snapshot and must
 * never be persisted or treated as stable identities.
 */
sealed interface SnapTargetAssessment {

    /** The focused task is a structurally-verified child of the active desk root. */
    data class Found(
        val displayId: Int,
        val activeDeskRootId: Int,
        val targetTaskId: Int,
        val packageName: String?,
        val componentName: String?,
    ) : SnapTargetAssessment

    /**
     * No safe target exists in this snapshot. Deliberately reason-carrying:
     * "no target" covers many distinct situations (no focus, focus outside
     * the active hierarchy, focus on DexZones itself, invisible task,
     * inactive desktop) and the diagnostics must say which one.
     */
    data class NoTarget(val reason: String) : SnapTargetAssessment

    /** The display's active-desktop assessment was ambiguous; propagated, never guessed through. */
    data class Ambiguous(val reason: String) : SnapTargetAssessment

    /** No supported desk topology on this display; nothing to target. */
    data class Unsupported(val reason: String) : SnapTargetAssessment
}

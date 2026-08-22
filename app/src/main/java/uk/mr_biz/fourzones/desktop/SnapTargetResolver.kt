package uk.mr_biz.fourzones.desktop

/**
 * Pure, framework-free resolver for the read-only Phase 2C1 question: is
 * the currently focused application task a safe snap target within a
 * positively-resolved active desktop?
 *
 * Decision inputs are the parsed [DesktopTopologySnapshot] only — never
 * dumpsys text, never Android APIs. The Phase 2B active-desktop resolution
 * ([DesktopTopologySnapshot.activeDesktopByDisplay]) is consumed as-is and
 * never re-derived or overridden here.
 *
 * Hard rules:
 *  - membership is purely structural (the parsed desk-root child lists,
 *    which come from hierarchy back-references); task/root ID values,
 *    ID proximity, ordering, and display-ID values are never inputs;
 *  - tasks of other activatable roots, minimized roots, or roots on other
 *    displays are never selected;
 *  - the host app itself (identified by the CALLER-SUPPLIED package name,
 *    never hard-coded here) is never a target;
 *  - explicitly invisible tasks are never targets; unknown visibility is
 *    treated conservatively as no-target — visibility is never invented;
 *  - when the active desktop is None/Ambiguous/Unsupported, or evidence is
 *    otherwise insufficient, the answer is a conservative no-target: there
 *    are NO fallbacks (no topmost/most-recent/largest/first/nearest task).
 *
 * Focus scoping precedence (a display's candidate focused task):
 *  1. display-scoped focus ([DesktopTopologySnapshot.focusedTaskByDisplay])
 *     is AUTHORITATIVE for that display when present; `Conflicting` fails
 *     closed;
 *  2. otherwise the legacy GLOBAL focus is used ONLY when the dump carried no
 *     display-scoped focus GRAMMAR at all
 *     ([DesktopTopologySnapshot.hasDisplayScopedFocusEvidence] is false), the
 *     dump observed at most one PHYSICAL display
 *     ([DesktopTopologySnapshot.observedDisplayIds]), AND exactly one display
 *     has an active desktop, so the global focus unambiguously belongs to it
 *     (the A9 standalone single-display case). If a second physical display is
 *     present — even one with no desk root, e.g. the phone display in external
 *     S25 DeX — the global focus may belong to THAT display and is never
 *     applied here. A scoped grammar that produced no usable focus
 *     (null/malformed mFocusedApp) is likewise NOT this case and fails closed;
 *  3. otherwise fail closed — a global (e.g. phone-display) focus is NEVER
 *     applied across multiple displays, and never suppresses a positively
 *     known scoped focus.
 * The structural membership check still applies to whichever task is chosen.
 */
object SnapTargetResolver {

    /**
     * Resolves one assessment per display present in
     * [DesktopTopologySnapshot.activeDesktopByDisplay], independently.
     *
     * @param selfPackageName the host application's own package identity;
     * passed in so the pure resolver embeds no application constant.
     */
    fun resolve(
        snapshot: DesktopTopologySnapshot,
        selfPackageName: String,
    ): Map<Int, SnapTargetAssessment> =
        snapshot.activeDesktopByDisplay.mapValues { (displayId, assessment) ->
            resolveForDisplay(snapshot, displayId, assessment, selfPackageName)
        }

    private fun resolveForDisplay(
        snapshot: DesktopTopologySnapshot,
        displayId: Int,
        assessment: ActiveDesktopAssessment,
        selfPackageName: String,
    ): SnapTargetAssessment {
        val activeRootId = when (assessment) {
            is ActiveDesktopAssessment.Found -> assessment.rootTaskId
            ActiveDesktopAssessment.None -> return SnapTargetAssessment.NoTarget(
                "no active desktop on display $displayId; a target cannot be manufactured",
            )
            is ActiveDesktopAssessment.Ambiguous -> return SnapTargetAssessment.Ambiguous(
                "active desktop on display $displayId is ambiguous " +
                    "(${assessment.candidateRootTaskIds}); not guessing a target",
            )
            ActiveDesktopAssessment.Unsupported -> return SnapTargetAssessment.Unsupported(
                "no supported desk topology on display $displayId",
            )
        }

        val focusedTaskId = when (val scoped = snapshot.focusedTaskByDisplay[displayId]) {
            is DisplayFocus.Task -> scoped.taskId
            DisplayFocus.Conflicting -> return SnapTargetAssessment.NoTarget(
                "display $displayId reported conflicting focused tasks; failing closed",
            )
            null -> {
                // Legacy fallback ONLY when there was no scoped-focus grammar
                // at all (not merely an empty map), the dump saw at most one
                // physical display, and a single active display. On a
                // multi-display dump (e.g. external S25 DeX: phone display +
                // monitor) the global focus can belong to another display, so
                // it is never applied here — otherwise a phone-side focus would
                // masquerade as the external desktop's target.
                val singleDisplaySystem = snapshot.observedDisplayIds.size <= 1
                val singleLegacyDisplay = !snapshot.hasDisplayScopedFocusEvidence &&
                    snapshot.activeDesktopByDisplay.size == 1 &&
                    singleDisplaySystem
                val legacy = snapshot.focusedTaskId
                when {
                    singleLegacyDisplay && legacy != null -> legacy
                    singleLegacyDisplay -> return SnapTargetAssessment.NoTarget(
                        "no focused task reported in this snapshot",
                    )
                    snapshot.hasDisplayScopedFocusEvidence -> return SnapTargetAssessment.NoTarget(
                        "display $displayId reported no usable scoped focus " +
                            "(null/malformed/absent); failing closed rather than using a " +
                            "global focus",
                    )
                    else -> return SnapTargetAssessment.NoTarget(
                        "no display-scoped focus for display $displayId; not applying a " +
                            "global focus across multiple displays",
                    )
                }
            }
        }

        // Structural resolution: the focused task counts only if it is a
        // child of THIS display's Found active desk root, per the parsed
        // hierarchy. Searching only that root's children makes selecting a
        // task of any other root (activatable, minimized, other display)
        // impossible by construction.
        val activeRoot = snapshot.roots.firstOrNull {
            it.rootTaskId == activeRootId && it.displayId == displayId
        } ?: return SnapTargetAssessment.NoTarget(
            "active desk root $activeRootId not present in the parsed hierarchy",
        )

        val target = activeRoot.childTasks.firstOrNull { it.taskId == focusedTaskId }
            ?: return SnapTargetAssessment.NoTarget(
                describeOutsideFocus(snapshot, focusedTaskId, activeRootId),
            )

        if (isSelf(target, selfPackageName)) {
            return SnapTargetAssessment.NoTarget(
                "focused task $focusedTaskId is the host app itself; never a snap target",
            )
        }

        return when (target.visible) {
            false -> SnapTargetAssessment.NoTarget(
                "focused task $focusedTaskId is explicitly not visible",
            )
            null -> SnapTargetAssessment.NoTarget(
                "focused task $focusedTaskId has unknown visibility; treated " +
                    "conservatively as no-target rather than inventing visibility",
            )
            true -> SnapTargetAssessment.Found(
                displayId = displayId,
                activeDeskRootId = activeRootId,
                targetTaskId = target.taskId,
                packageName = target.packageName,
                componentName = target.componentName,
            )
        }
    }

    private fun isSelf(task: DesktopTask, selfPackageName: String): Boolean =
        task.packageName == selfPackageName ||
            task.componentName?.substringBefore('/') == selfPackageName

    /**
     * Diagnostic-only description of where the focused task actually lives.
     * The DECISION was already made structurally (it is not in the active
     * root); this only improves the reason text.
     */
    private fun describeOutsideFocus(
        snapshot: DesktopTopologySnapshot,
        focusedTaskId: Int,
        activeRootId: Int,
    ): String {
        val owner = snapshot.roots.firstOrNull { root ->
            root.childTasks.any { it.taskId == focusedTaskId }
        }
        return if (owner != null) {
            "focused task $focusedTaskId belongs to desk root ${owner.rootTaskId} " +
                "(${owner.type}), not the active desk root $activeRootId"
        } else {
            "focused task $focusedTaskId is outside the desk-root hierarchy " +
                "(launcher/system or non-desktop task); not a snap target"
        }
    }
}

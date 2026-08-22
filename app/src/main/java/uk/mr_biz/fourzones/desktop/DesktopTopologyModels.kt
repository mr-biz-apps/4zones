package uk.mr_biz.fourzones.desktop

/**
 * Pure, framework-free model of Samsung desktop (desk-root) topology as
 * observed in read-only activity dumps. Immutable snapshots; nothing here is
 * ever derived from task-ID values, ID ordering, or ID proximity — IDs are
 * opaque handles recovered from the dump text.
 */

/** Runtime-observed desk-root type from mDeskRootTaskType / dw fields. */
enum class DeskRootType {
    /** mDeskRootTaskType=activatable — a desktop that can be the active one. */
    ACTIVATABLE,

    /** mDeskRootTaskType=minimized — a minimized desk root; never active. */
    MINIMIZED,

    /**
     * The desk-root field was present with an unrecognized value. Recorded
     * as-is and treated conservatively: never a candidate for anything.
     */
    UNKNOWN,
}

/**
 * Observed OUTER task bounds (dumpsys `mBounds=Rect(l, t - r, b)`), decoupled
 * from android.graphics.Rect. Diagnostic/additive only: bounds NEVER affect
 * active-desktop selection, minimized-root interpretation, the visibility
 * truth table, or target-resolver selection. Used solely for Phase 2C3A
 * postcondition verification. Absent when the dump did not expose bounds or
 * they were malformed — never invented.
 */
data class TaskBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    override fun toString(): String = "($left,$top)-($right,$bottom)"
}

/** An application task hosted inside a desktop root. */
data class DesktopTask(
    val taskId: Int,
    val packageName: String?,
    val componentName: String?,
    val visible: Boolean?,
    /** True only if this task matches mFocusedApp. Diagnostic; never active-desktop input. */
    val focused: Boolean?,
    /** Observed outer bounds if the dump exposed them; diagnostic-only (see [TaskBounds]). */
    val bounds: TaskBounds? = null,
)

/**
 * One Samsung desk root task. Booleans are nullable: null means the dump did
 * not expose the field, which must never collapse into false.
 */
data class DesktopRoot(
    val rootTaskId: Int,
    /** Display the root was listed under. Data only — never interpreted. */
    val displayId: Int?,
    val type: DeskRootType,
    val visible: Boolean?,
    val visibleRequested: Boolean?,
    val createdByOrganizer: Boolean?,
    val forceHidden: Boolean?,
    /** Raw windowing mode token from the dump (e.g. "freeform"); data only. */
    val windowingMode: String?,
    /** Membership recovered from the dump hierarchy, never from ID proximity. */
    val childTasks: List<DesktopTask>,
)

/**
 * Conservative active-desktop verdict, scoped to ONE display. The only
 * positive evidence accepted is: an ACTIVATABLE desk root whose visibility
 * is positively consistent (visible and visibleRequested agree or one is
 * unavailable — contradictory transitional states are never positive).
 * Focus fields, display-ID values, ID ordering and ID numbering are never
 * inputs.
 */
sealed interface ActiveDesktopAssessment {
    /** Exactly one activatable desk root is visibly active. */
    data class Found(val rootTaskId: Int) : ActiveDesktopAssessment

    /** Desk topology exists but no activatable root is visibly active. */
    data object None : ActiveDesktopAssessment

    /** More than one activatable root appears visibly active. */
    data class Ambiguous(val candidateRootTaskIds: List<Int>) : ActiveDesktopAssessment

    /** No recognized Samsung desk-root fields observed; do not guess. */
    data object Unsupported : ActiveDesktopAssessment
}

/**
 * Display-scoped focused-app evidence, from a WindowManager
 * `Display: mDisplayId=N` block's `mFocusedApp=...t<id>` line. Focus is used
 * ONLY to identify a candidate task WITHIN an already-resolved active desktop;
 * it is NEVER an input to active-desktop identity.
 */
sealed interface DisplayFocus {
    /** Exactly one focused task observed for this display. */
    data class Task(val taskId: Int) : DisplayFocus

    /** Two or more differing focused tasks reported for one display: fail closed. */
    data object Conflicting : DisplayFocus
}

/** Parsed topology with its conservative interpretation and visible reasoning. */
data class DesktopTopologySnapshot(
    /** All desk roots (activatable, minimized, unknown) in document order. */
    val roots: List<DesktopRoot>,
    /**
     * The authoritative active-desktop result: one independent assessment per
     * observed display. Display IDs are scope/correlation keys ONLY — they
     * carry no ordering or capability meaning, and each display has one
     * active desktop at most regardless of what other displays host. Roots
     * without a known display association are never silently assigned to a
     * display; they are excluded and reported in [evidence]. An empty map
     * means no desk topology was observed anywhere (unsupported).
     */
    val activeDesktopByDisplay: Map<Int, ActiveDesktopAssessment>,
    /**
     * Legacy GLOBAL focused task (the last `mFocusedApp` seen). Diagnostic,
     * and a conservative single-display fallback only (see SnapTargetResolver).
     */
    val focusedTaskId: Int?,
    /**
     * Display-scoped focus from the WindowManager `Display: mDisplayId=N`
     * blocks. Authoritative per display for target identification; an absent
     * entry means that display reported no usable scoped focus (its
     * mFocusedApp was null, malformed, or absent). Emptiness alone does NOT
     * mean the scoped grammar was missing — see [hasDisplayScopedFocusEvidence].
     */
    val focusedTaskByDisplay: Map<Int, DisplayFocus> = emptyMap(),
    /**
     * True when at least one WindowManager `Display: mDisplayId=N` block was
     * observed, regardless of whether it yielded a usable mFocusedApp. This
     * distinguishes "no scoped-focus grammar at all" (legacy A9 input, where
     * the global fallback may apply) from "scoped grammar present but no
     * usable focus" (must fail closed). Never infer this from an empty map.
     */
    val hasDisplayScopedFocusEvidence: Boolean = false,
    /**
     * Every physical display observed in the dump's task-hierarchy
     * (`Display #N (activities from top to bottom)`) sections — NOT only the
     * displays that carry a desk root. On standalone A9 DeX this is a single
     * display; on external S25 DeX both the phone display and the external
     * monitor appear here even though only the monitor hosts a Desk.
     *
     * This distinguishes a genuinely single-display system (where the legacy
     * GLOBAL focus unambiguously belongs to the sole display) from a
     * multi-display system (where a global focus may belong to a DIFFERENT
     * display and must never be applied to another display's desktop). See the
     * legacy-fallback guard in SnapTargetResolver. Display-ID VALUES carry no
     * meaning here; only the count/membership is used, and never for identity.
     */
    val observedDisplayIds: Set<Int> = emptySet(),
    val evidence: List<String>,
) {
    val activatableRoots: List<DesktopRoot>
        get() = roots.filter { it.type == DeskRootType.ACTIVATABLE }

    val minimizedRoots: List<DesktopRoot>
        get() = roots.filter { it.type == DeskRootType.MINIMIZED }
}

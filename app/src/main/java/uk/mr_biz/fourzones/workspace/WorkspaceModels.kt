package uk.mr_biz.fourzones.workspace

/**
 * Pure, framework-free model of what public Android APIs expose about the
 * windowing environment of the currently running Activity. Instances are
 * immutable snapshots; a change on the device produces a new instance.
 *
 * This model deliberately contains no "isDex"/"isDesktop" Boolean: Phase 1
 * showed that display properties cannot establish that, and none of the
 * fields below individually prove it either. Interpretation lives in
 * [WorkspaceInterpreter] and is evidence-oriented, never a desktop verdict.
 */
data class WorkspaceState(
    /**
     * Display ID the Activity's context reports as its hosting display, or
     * null when the Activity is not currently associated with a display.
     * Diagnostic data only: no interpretation may depend on its value.
     */
    val hostingDisplayId: Int?,
    /**
     * Activity.isInMultiWindowMode(). True in split-screen, freeform,
     * picture-in-picture and desktop-style windowing alike — it never
     * identifies WHICH multi-window mode is active.
     */
    val isInMultiWindowMode: Boolean,
    /** Activity.isInPictureInPictureMode(). Used to exclude PiP as an explanation. */
    val isInPictureInPictureMode: Boolean,
    /**
     * PackageManager.hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT).
     * DEVICE CAPABILITY only: says the device can support freeform windows,
     * never that freeform/desktop windowing is currently active.
     */
    val supportsFreeformWindowManagement: Boolean,
    /** UiModeManager.currentModeType, mapped to a readable name. */
    val uiModeType: UiModeType,
    /**
     * currentWindowMetrics.bounds: the WINDOW bounds of this Activity in
     * screen coordinates — not physical display dimensions.
     */
    val currentBounds: BoundsSnapshot,
    /**
     * maximumWindowMetrics.bounds: the largest window area the system could
     * give this Activity on its current display. Also window coordinates,
     * not physical panel pixels.
     */
    val maximumBounds: BoundsSnapshot,
    /** Raw system insets from currentWindowMetrics.windowInsets; diagnostic only. */
    val insets: WorkspaceInsets,
    /** Publicly observable Configuration values for this Activity. */
    val configuration: ConfigurationSnapshot,
) {
    /**
     * Whether the current window bounds differ from the maximum bounds.
     * Evidence of a non-maximized/restricted window (split-screen, freeform,
     * PiP, letterboxing all qualify) — never proof of desktop mode.
     */
    val currentDiffersFromMaximum: Boolean
        get() = currentBounds != maximumBounds
}

/** Rectangle in window coordinates, decoupled from android.graphics.Rect. */
data class BoundsSnapshot(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    override fun toString(): String = "($left,$top - $right,$bottom) ${width}x$height"
}

/** Per-edge inset values in pixels, decoupled from android.graphics.Insets. */
data class InsetValues(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    override fun toString(): String = "l=$left t=$top r=$right b=$bottom"
}

/** Raw inset values kept for later geometry diagnostics; no work-area math yet. */
data class WorkspaceInsets(
    val statusBars: InsetValues,
    val navigationBars: InsetValues,
    val systemBars: InsetValues,
    val displayCutout: InsetValues,
)

/** Observable Configuration values, decoupled from android.content.res.Configuration. */
data class ConfigurationSnapshot(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val smallestScreenWidthDp: Int,
    val orientation: ScreenOrientation,
)

/** Configuration.ORIENTATION_* decoupled from framework ints. */
enum class ScreenOrientation {
    PORTRAIT,
    LANDSCAPE,
    UNDEFINED,
}

/**
 * Configuration.UI_MODE_TYPE_* mapped to readable names. DESK means Android
 * reported UI_MODE_TYPE_DESK — an explicit public signal worth recording,
 * but NOT equated with Samsung DeX or any vendor desktop mode.
 */
enum class UiModeType {
    NORMAL,
    DESK,
    CAR,
    TELEVISION,
    APPLIANCE,
    WATCH,
    VR_HEADSET,
    UNDEFINED,

    /** A framework value this milestone does not know; recorded, not guessed at. */
    UNKNOWN,
}

/**
 * Conservative overall assessment of the captured signals. There is
 * deliberately no ACTIVE_DESKTOP value: none of the public signals captured
 * in this milestone can prove an active desktop/DeX workspace.
 */
enum class WorkspaceAssessment {
    /**
     * At least one observed signal is consistent with a desktop-style
     * windowing environment. Consistent-with, not proof: split-screen and
     * other non-desktop states produce the same signals.
     */
    EVIDENCE_PRESENT,

    /** No observed signal distinguishes this environment from a plain fullscreen Activity. */
    UNDETERMINED,
}

/** Assessment plus the reasoning that produced it, kept visible for diagnostics. */
data class WorkspaceInterpretation(
    val assessment: WorkspaceAssessment,
    val evidence: List<String>,
)

/** A captured workspace snapshot with its interpretation, mirroring DisplayInfo. */
data class WorkspaceSnapshot(
    val state: WorkspaceState,
    val interpretation: WorkspaceInterpretation,
)

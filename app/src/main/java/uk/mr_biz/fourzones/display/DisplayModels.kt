package uk.mr_biz.fourzones.display

/**
 * Pure, framework-free model of a discovered display. Instances are immutable
 * snapshots; a change on the device produces a new instance, never a mutation.
 */
data class DisplayProperties(
    val displayId: Int,
    val name: String,
    /**
     * Physical pixel width/height of the display's active mode
     * (Display.getMode()). These are panel dimensions, rotation-independent;
     * they are NOT window/application bounds and NOT density-scaled logical
     * dimensions.
     */
    val physicalWidthPx: Int,
    val physicalHeightPx: Int,
    val rotation: DisplayRotation,
    val refreshRateHz: Float,
    val densityDpi: Int,
    val flags: Set<DisplayFlag>,
    val isDefaultDisplay: Boolean,
    val inPresentationCategory: Boolean,
    /**
     * Result of Display.isInternal(), or null when the running API level does
     * not expose it (available from API 36.1). Null means genuinely unknown,
     * never false.
     */
    val isInternal: Boolean?,
)

/** Display rotation expressed in degrees, decoupled from Surface.ROTATION_* ints. */
enum class DisplayRotation(val degrees: Int) {
    DEG_0(0),
    DEG_90(90),
    DEG_180(180),
    DEG_270(270),
}

/** Publicly observable Display flags relevant to classification. */
enum class DisplayFlag {
    PRESENTATION,
    PRIVATE,
    SECURE,
    ROUND,
    SUPPORTS_PROTECTED_BUFFERS,
}

/**
 * Where a display comes from, as far as public APIs can tell. Deliberately
 * tri-state: when evidence is insufficient the answer is UNDETERMINED, never
 * a guessed Boolean.
 */
enum class DisplayOrigin {
    /** Display.isInternal() reports an internal (built-in) display. */
    BUILT_IN,

    /**
     * Display.isInternal() reports a non-internal display. Public APIs do not
     * say which kind: wired, wireless, overlay, or virtual are all possible,
     * so this is intentionally not called "external".
     */
    NON_INTERNAL,

    /** Public evidence is insufficient (notably pre-36.1 runtimes). */
    UNDETERMINED,
}

/**
 * Whether a display could host a desktop-style workspace. Deliberately
 * tri-state: public APIs often cannot answer this (notably for built-in
 * displays, which may host standalone desktop mode), and "unknown" must not
 * collapse into "no".
 */
enum class DesktopCapability {
    /** Positive public evidence it is a suitable desktop-workspace candidate. */
    CANDIDATE,

    /** Public APIs available in this milestone cannot determine it. */
    UNDETERMINED,

    /** Positive public evidence against (e.g. private, no usable size). */
    NOT_A_CANDIDATE,
}

/**
 * Capability-based classification of a display, with the evidence that led to
 * each conclusion so the reasoning stays visible in diagnostics.
 *
 * Origin, presentation capability, privacy, default-display status (in
 * [DisplayProperties]), and desktop capability are independent concepts:
 * presentation APIs are never proof of origin or of desktop mode, and privacy
 * never erases presentation capability.
 */
data class DisplayClassification(
    val origin: DisplayOrigin,
    val isPresentationCapable: Boolean,
    val isPrivate: Boolean,
    val desktopCapability: DesktopCapability,
    val evidence: List<String>,
)

/** A discovered display: its raw public properties plus their classification. */
data class DisplayInfo(
    val properties: DisplayProperties,
    val classification: DisplayClassification,
)

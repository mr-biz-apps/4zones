package uk.mr_biz.fourzones.geometry

/**
 * Pure, framework-free geometry model for the DeX snap workspace and its
 * destination quadrants. All values are dynamically derived per display at
 * read time; no display ID, resolution, inset size, density, bar placement,
 * or orientation is ever a constant. Rectangles are project-owned immutable
 * integers with HALF-OPEN semantics [left, top, right, bottom) — never
 * android.graphics.Rect inside decision logic.
 *
 * Three DISTINCT concepts are modeled and must never be conflated:
 *  - display-level snap-workspace inputs (status bars, navigation bars,
 *    system overlays, display cutout) — excluded from the snap workspace;
 *  - task-local caption-bar evidence — on the tested A9 standalone-DeX and
 *    S25 external-DeX configurations, Samsung's task-local caption inset
 *    was observed INSIDE the snapped task outer rectangle; DexZones
 *    therefore keeps caption evidence separate from its display-level
 *    snap-workspace mask (a policy hardware-correlated against those
 *    tested configurations, not claimed as a universal Samsung/Android
 *    rule);
 *  - grid spacing — the intentional gap between snap destinations.
 */
data class GeometryRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /**
     * The ONLY span helpers. Coordinates are Int, but a valid rectangle may
     * span the full Int coordinate range (e.g. Int.MIN_VALUE..Int.MAX_VALUE),
     * so its span can exceed Int.MAX_VALUE even though every coordinate is
     * representable — spans therefore require Long. These are safe for
     * ARBITRARY valid GeometryRect values and can never wrap. There is
     * deliberately no Int width/height helper.
     */
    val widthLong: Long get() = right.toLong() - left.toLong()
    val heightLong: Long get() = bottom.toLong() - top.toLong()

    /** Wrap-safe: comparisons only, no arithmetic. */
    val isEmpty: Boolean get() = right <= left || bottom <= top

    /** Wrap-safe: comparisons only, no arithmetic. */
    fun contains(other: GeometryRect): Boolean =
        other.left >= left && other.top >= top &&
            other.right <= right && other.bottom <= bottom

    override fun toString(): String = "($left,$top)-($right,$bottom), ${widthLong}x$heightLong"
}

/** Per-edge inset values in pixels. */
data class GeometryInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val hasNegative: Boolean get() = left < 0 || top < 0 || right < 0 || bottom < 0

    override fun toString(): String = "l=$left t=$top r=$right b=$bottom"

    companion object {
        val NONE = GeometryInsets(0, 0, 0, 0)

        /** Per-edge union of two exclusion masks (WindowInsets max semantics). */
        fun union(a: GeometryInsets, b: GeometryInsets) = GeometryInsets(
            left = maxOf(a.left, b.left),
            top = maxOf(a.top, b.top),
            right = maxOf(a.right, b.right),
            bottom = maxOf(a.bottom, b.bottom),
        )
    }
}

/** The four snap destinations derived from a snap workspace. */
enum class Quadrant {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

/**
 * Raw typed reading from the Android adapter for ONE display: maximum
 * window metrics, the stable (visibility-ignoring) inset components — each
 * retained separately as diagnostic evidence — and the display's logical
 * density from the same display-specific context. The IME is deliberately
 * absent — a software keyboard must never shrink desktop geometry.
 */
data class DisplayGeometryReading(
    /** Correlation/scope key only; never an input to any calculation. */
    val displayId: Int,
    val maximumBounds: GeometryRect,
    val statusBarInsets: GeometryInsets,
    val navigationBarInsets: GeometryInsets,
    /**
     * Task-local decoration evidence: on the tested DeX configurations the
     * caption inset was observed inside the snapped task outer rectangle,
     * so DexZones keeps it OUT of the display-level snap-workspace mask.
     * Retained for diagnostics only; never a mask input.
     */
    val captionBarInsets: GeometryInsets,
    /**
     * RAW combined system-bars value exactly as the platform reports it
     * (statusBars | navigationBars | captionBar | systemOverlays).
     * DIAGNOSTIC EVIDENCE ONLY — because it contains the caption
     * contribution it must never be applied to the snap workspace; the
     * applied mask is built from the individual components instead.
     */
    val systemBarInsets: GeometryInsets,
    /**
     * System-overlay insets (API 34+ source; zero on earlier platforms).
     * A display-level snap-workspace mask input.
     */
    val systemOverlayInsets: GeometryInsets,
    val displayCutoutInsets: GeometryInsets,
    /**
     * Logical density scale (dp→px factor) of this display, from the same
     * display-specific context used for metrics — never inferred from
     * resolution or model.
     */
    val densityScale: Float,
    /** Density in dpi, diagnostic display only. */
    val densityDpi: Int,
)

/**
 * Assessment of one display's snap workspace and destination quadrants.
 *
 * The snap-workspace rule (maximumBounds minus the per-edge union of
 * status bars, navigation bars, system overlays and display cutout) is a
 * HARDWARE-VALIDATED snap-workspace policy for the tested DeX
 * configurations — it reproduced the outer edges of Samsung native
 * half-snap task bounds exactly on A9 standalone DeX and on the S25 +
 * external DeX monitor. It is not claimed to be universally authoritative
 * Samsung geometry beyond that evidence. Maximized-task geometry is a
 * distinct policy (tasks may occupy maximum display bounds underneath
 * system bars) and is deliberately NOT modeled here.
 *
 * Insufficient or contradictory inputs produce [Invalid]/[Unsupported] —
 * geometry is never manufactured.
 */
sealed interface DesktopWorkAreaAssessment {

    val displayId: Int

    data class Found(
        val reading: DisplayGeometryReading,
        /**
         * Applied display-level exclusion: per-edge union of statusBars,
         * navigationBars, systemOverlays and displayCutout. Never
         * captionBar, never raw systemBars, never IME.
         */
        val snapWorkspaceInsets: GeometryInsets,
        /** maximumBounds shrunk by [snapWorkspaceInsets] on all four edges. */
        val snapWorkspace: GeometryRect,
        /** The grid-gap policy resolved to pixels at this display's density. */
        val resolvedGapPx: Int,
        /**
         * Destination rectangles separated by the intentional centre gaps.
         * They deliberately do NOT tile [snapWorkspace]: the uncovered area
         * is exactly the configured horizontal/vertical gap structure.
         */
        val destinationQuadrants: Map<Quadrant, GeometryRect>,
    ) : DesktopWorkAreaAssessment {
        override val displayId: Int get() = reading.displayId
    }

    /** Inputs were readable but produce no defensible snap geometry. */
    data class Invalid(
        override val displayId: Int,
        val reason: String,
    ) : DesktopWorkAreaAssessment

    /** The platform could not supply geometry for this display. */
    data class Unsupported(
        override val displayId: Int,
        val reason: String,
    ) : DesktopWorkAreaAssessment
}

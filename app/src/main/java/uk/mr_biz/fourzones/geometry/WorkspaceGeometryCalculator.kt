package uk.mr_biz.fourzones.geometry

/**
 * Pure, Android-independent snap-workspace and destination-quadrant
 * calculator.
 *
 * Snap-workspace rule (hardware-validated for the tested DeX
 * configurations — A9 standalone DeX and S25 external DeX — where it
 * reproduced Samsung native half-snap outer bounds exactly; not claimed
 * universal beyond that evidence):
 *
 *     snapWorkspaceInsets = per-edge max(statusBars, navigationBars,
 *                                        systemOverlays, displayCutout)
 *     snapWorkspace       = maximumBounds minus snapWorkspaceInsets
 *
 * Union, not sum: a cutout typically overlaps the status-bar region and
 * summing would double-count. The mask is built STRUCTURALLY from the
 * individual components: the platform's raw combined systemBars value
 * includes the caption bar and therefore never participates — it is
 * retained as diagnostic evidence only. Caption evidence: on the tested
 * configurations Samsung's task-local caption inset was observed inside
 * the snapped task outer rectangle, so DexZones keeps captionBar out of
 * its display-level mask (hardware-correlated policy for those tested
 * configurations, not a claimed universal Samsung/Android rule). IME and
 * transient UI likewise never participate. No assumption that bounds start
 * at (0,0), that a taskbar is at the bottom, or that a status bar is at
 * the top.
 *
 * Destination quadrants are separated by the [SnapGridPolicy] grid gap,
 * resolved to pixels at the display's own density, split symmetrically
 * around the workspace midpoint (odd workspace remainders to the
 * right/bottom via floor midpoints; odd pixel gaps put the extra pixel on
 * the right/bottom side of the gap). The destinations deliberately do NOT
 * tile the workspace: the uncovered area is exactly the intentional centre
 * gap structure.
 *
 * Overflow safety (fail closed): all intermediate arithmetic is Long, and
 * every resulting coordinate must be representable as Int before any
 * [GeometryRect] is constructed. Malformed extreme inputs produce
 * [DesktopWorkAreaAssessment.Invalid] — never clamped, never wrapped, never
 * manufactured. The display ID is a correlation key only.
 */
object WorkspaceGeometryCalculator {

    fun calculate(reading: DisplayGeometryReading): DesktopWorkAreaAssessment {
        val bounds = reading.maximumBounds

        // All arithmetic below is Long; Int inputs are widened once here.
        val sourceLeft = bounds.left.toLong()
        val sourceTop = bounds.top.toLong()
        val sourceRight = bounds.right.toLong()
        val sourceBottom = bounds.bottom.toLong()

        // Invalid source bounds — pure comparisons, no wrappable widths.
        if (sourceRight <= sourceLeft || sourceBottom <= sourceTop) {
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "maximum bounds are empty ($bounds)",
            )
        }

        // A negative edge in ANY retained inset component fails closed —
        // even when the combined systemBars value is nonnegative. Integrity
        // check on the complete reading; the applied snap mask is the
        // per-edge max of statusBarInsets, navigationBarInsets,
        // systemOverlayInsets and displayCutoutInsets. Raw systemBarInsets
        // is validated here but diagnostic-only (it includes captionBar);
        // captionBarInsets is task-local diagnostic evidence and is not an
        // applied snap-workspace input. Malformed component data is never
        // normalized, clamped, or ignored.
        val insetComponents = listOf(
            "status-bar" to reading.statusBarInsets,
            "navigation-bar" to reading.navigationBarInsets,
            "caption-bar" to reading.captionBarInsets,
            "system-bar" to reading.systemBarInsets,
            "system-overlay" to reading.systemOverlayInsets,
            "display-cutout" to reading.displayCutoutInsets,
        )
        insetComponents.firstOrNull { (_, insets) -> insets.hasNegative }?.let { (name, _) ->
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "negative $name inset component reported; not manufacturing geometry",
            )
        }

        // Density must be a usable dp→px factor; the gap policy depends on it.
        val density = reading.densityScale
        if (!density.isFinite() || density <= 0f) {
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "display density scale is not a finite positive value; " +
                    "not manufacturing geometry",
            )
        }

        // Applied mask from INDIVIDUAL components only. Raw systemBars is
        // diagnostic (it contains captionBar); captionBar is task-local
        // evidence; neither may enter this union.
        val snapInsets = GeometryInsets.union(
            GeometryInsets.union(reading.statusBarInsets, reading.navigationBarInsets),
            GeometryInsets.union(reading.systemOverlayInsets, reading.displayCutoutInsets),
        )

        // Inset-adjusted edges in Long (cannot overflow Long from Int inputs).
        val workLeft = sourceLeft + snapInsets.left.toLong()
        val workTop = sourceTop + snapInsets.top.toLong()
        val workRight = sourceRight - snapInsets.right.toLong()
        val workBottom = sourceBottom - snapInsets.bottom.toLong()

        if (workRight <= workLeft) {
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "insets consume all horizontal space " +
                    "(bounds $bounds, snap insets $snapInsets)",
            )
        }
        if (workBottom <= workTop) {
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "insets consume all vertical space " +
                    "(bounds $bounds, snap insets $snapInsets)",
            )
        }

        // Grid gap resolved at this display's density (dp→px, rounded).
        // Conversion lives in SnapGridPolicy, outside the rect arithmetic,
        // and fails closed: an unrepresentable gap (e.g. an absurd finite
        // density) is Invalid, never a saturated or wrapped pixel value.
        val gap = SnapGridPolicy.gapPixels(density)?.toLong()
            ?: return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "grid gap is not representable at this display density; " +
                    "not manufacturing geometry",
            )

        // Symmetric split around the floor midpoint; for an odd gap the
        // extra pixel goes to the right/bottom side of the gap.
        val remainingWidth = workRight - workLeft
        val remainingHeight = workBottom - workTop
        val midX = workLeft + remainingWidth / 2
        val midY = workTop + remainingHeight / 2
        val halfGapLeft = gap / 2
        val halfGapRight = gap - halfGapLeft
        val xGapStart = midX - halfGapLeft
        val xGapEnd = midX + halfGapRight
        val yGapStart = midY - halfGapLeft
        val yGapEnd = midY + halfGapRight

        // Every destination must be non-empty after the gap is applied.
        if (xGapStart <= workLeft || xGapEnd >= workRight) {
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "snap workspace ${remainingWidth}x$remainingHeight too small for the " +
                    "horizontal grid gap ($gap px); not manufacturing geometry",
            )
        }
        if (yGapStart <= workTop || yGapEnd >= workBottom) {
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "snap workspace ${remainingWidth}x$remainingHeight too small for the " +
                    "vertical grid gap ($gap px); not manufacturing geometry",
            )
        }

        // Every coordinate must be representable as Int before any rectangle
        // exists. Out-of-range values fail closed — no clamping, no wrapping.
        val coordinates = longArrayOf(
            workLeft, workTop, workRight, workBottom,
            xGapStart, xGapEnd, yGapStart, yGapEnd,
        )
        if (coordinates.any { it < Int.MIN_VALUE || it > Int.MAX_VALUE }) {
            return DesktopWorkAreaAssessment.Invalid(
                reading.displayId,
                "snap-workspace coordinates exceed the representable integer range; " +
                    "not manufacturing geometry",
            )
        }

        val workspace = GeometryRect(
            left = workLeft.toInt(),
            top = workTop.toInt(),
            right = workRight.toInt(),
            bottom = workBottom.toInt(),
        )
        val gxStart = xGapStart.toInt()
        val gxEnd = xGapEnd.toInt()
        val gyStart = yGapStart.toInt()
        val gyEnd = yGapEnd.toInt()
        val destinations = mapOf(
            Quadrant.TOP_LEFT to GeometryRect(workspace.left, workspace.top, gxStart, gyStart),
            Quadrant.TOP_RIGHT to GeometryRect(gxEnd, workspace.top, workspace.right, gyStart),
            Quadrant.BOTTOM_LEFT to GeometryRect(workspace.left, gyEnd, gxStart, workspace.bottom),
            Quadrant.BOTTOM_RIGHT to GeometryRect(gxEnd, gyEnd, workspace.right, workspace.bottom),
        )

        return DesktopWorkAreaAssessment.Found(
            reading = reading,
            snapWorkspaceInsets = snapInsets,
            snapWorkspace = workspace,
            resolvedGapPx = gap.toInt(),
            destinationQuadrants = destinations,
        )
    }
}

package uk.mr_biz.fourzones.geometry

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the snap-workspace/destination calculator. All
 * coordinates are synthetic; where hardware-observed shapes (A9 1920x1200 @
 * 1.5, Qreator 3840x2160 @ 1.0) appear they are validation fixtures only
 * and carry no semantic meaning in production — a dedicated test proves
 * arbitrary unusual dimensions follow the identical formula, and no
 * production code references these resolutions, IDs, or pixel gaps.
 */
class WorkspaceGeometryCalculatorTest {

    private fun reading(
        displayId: Int = 0,
        bounds: GeometryRect,
        status: GeometryInsets = GeometryInsets.NONE,
        navigation: GeometryInsets = GeometryInsets.NONE,
        caption: GeometryInsets = GeometryInsets.NONE,
        // Realistic raw platform value: systemBars combines status,
        // navigation AND caption (that is exactly why it is diagnostic-only).
        system: GeometryInsets = GeometryInsets.union(
            GeometryInsets.union(status, navigation),
            caption,
        ),
        overlay: GeometryInsets = GeometryInsets.NONE,
        cutout: GeometryInsets = GeometryInsets.NONE,
        density: Float = 1.0f,
        densityDpi: Int = 160,
    ) = DisplayGeometryReading(
        displayId = displayId,
        maximumBounds = bounds,
        statusBarInsets = status,
        navigationBarInsets = navigation,
        captionBarInsets = caption,
        systemBarInsets = system,
        systemOverlayInsets = overlay,
        displayCutoutInsets = cutout,
        densityScale = density,
        densityDpi = densityDpi,
    )

    private fun found(reading: DisplayGeometryReading): DesktopWorkAreaAssessment.Found =
        WorkspaceGeometryCalculator.calculate(reading) as DesktopWorkAreaAssessment.Found

    /**
     * Destination invariants: four non-empty rectangles inside the snap
     * workspace, no overlap, and the uncovered area EXACTLY equals the
     * intentional centre gap structure (cross of width/height = resolved
     * gap) — no accidental holes.
     */
    private fun assertDestinationInvariants(found: DesktopWorkAreaAssessment.Found) {
        val work = found.snapWorkspace
        val q = found.destinationQuadrants
        val gap = found.resolvedGapPx.toLong()
        assertEquals(Quadrant.entries.toSet(), q.keys)
        val tl = q.getValue(Quadrant.TOP_LEFT)
        val tr = q.getValue(Quadrant.TOP_RIGHT)
        val bl = q.getValue(Quadrant.BOTTOM_LEFT)
        val br = q.getValue(Quadrant.BOTTOM_RIGHT)

        // Non-empty, inside the workspace.
        q.values.forEach { rect -> assertTrue("empty destination $rect", !rect.isEmpty) }
        q.values.forEach { rect -> assertTrue("$rect escapes $work", work.contains(rect)) }
        // Outer edges pinned to the workspace.
        assertEquals(work.left, tl.left)
        assertEquals(work.left, bl.left)
        assertEquals(work.top, tl.top)
        assertEquals(work.top, tr.top)
        assertEquals(work.right, tr.right)
        assertEquals(work.right, br.right)
        assertEquals(work.bottom, bl.bottom)
        assertEquals(work.bottom, br.bottom)
        // Gap structure: columns/rows align and are separated by exactly gap.
        assertEquals(tl.right, bl.right)
        assertEquals(tr.left, br.left)
        assertEquals(tl.bottom, tr.bottom)
        assertEquals(bl.top, br.top)
        assertEquals(gap, tr.left.toLong() - tl.right.toLong())
        assertEquals(gap, bl.top.toLong() - tl.bottom.toLong())
        // No overlap: the columns are disjoint (left column ends at or
        // before the right column starts) and likewise the rows.
        assertTrue(tl.right <= tr.left)
        assertTrue(tl.bottom <= bl.top)
        // Exact intended-gap accounting: covered area is precisely
        // (W - gap) * (H - gap); the remainder is the gap cross alone.
        // BigInteger throughout: full-Int-span fixtures produce areas that
        // overflow Long multiplication, and wrapped values could compare
        // equal by accident.
        val totalArea = q.values.fold(BigInteger.ZERO) { acc, rect ->
            acc + BigInteger.valueOf(rect.widthLong) * BigInteger.valueOf(rect.heightLong)
        }
        val expectedArea =
            (BigInteger.valueOf(work.widthLong) - BigInteger.valueOf(gap)) *
                (BigInteger.valueOf(work.heightLong) - BigInteger.valueOf(gap))
        assertEquals(expectedArea, totalArea)
    }

    // ------------------------------------------------------- gap policy

    // 1. 8 dp at density 1.0 -> 8 px.
    @Test
    fun `8dp resolves to 8px at density 1_0`() {
        assertEquals(8, SnapGridPolicy.gapPixels(1.0f))
    }

    // 2. 8 dp at density 1.5 -> 12 px.
    @Test
    fun `8dp resolves to 12px at density 1_5`() {
        assertEquals(12, SnapGridPolicy.gapPixels(1.5f))
    }

    @Test
    fun `dp conversion uses standard half-up rounding`() {
        // 8 * 1.0625 = 8.5 -> rounds up to 9 (Math.round semantics).
        assertEquals(9, SnapGridPolicy.gapPixels(1.0625f))
        // 8 * 1.03 = 8.24 -> 8.
        assertEquals(8, SnapGridPolicy.gapPixels(1.03f))
    }

    @Test
    fun `unrepresentable gap resolution fails closed as null`() {
        // Finite but absurd density: the widened Double product exceeds Int
        // range, so resolution refuses — never a saturated/wrapped value.
        assertEquals(null, SnapGridPolicy.gapPixels(Float.MAX_VALUE))
        assertEquals(null, SnapGridPolicy.gapPixels(Float.NaN))
        assertEquals(null, SnapGridPolicy.gapPixels(Float.POSITIVE_INFINITY))
        assertEquals(null, SnapGridPolicy.gapPixels(0f))
        assertEquals(null, SnapGridPolicy.gapPixels(-1f))
    }

    @Test
    fun `extreme finite density is invalid through the complete calculator`() {
        // Must be Invalid end-to-end: a ridiculous-but-finite density can
        // never reach Found via a saturated gap.
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
                density = Float.MAX_VALUE,
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(
            "was: ${invalid.reason}",
            invalid.reason.contains("gap") && invalid.reason.contains("density"),
        )
    }

    // -------------------------------------------- hardware-shaped fixtures

    // 3/5. A9 standalone DeX shape: exact native horizontal edges and the
    // full symmetric-grid quadrants at density 1.5.
    @Test
    fun `a9 shape reproduces native horizontal snap edges and grid quadrants`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1920, 1200),
                status = GeometryInsets(0, 45, 0, 0),
                navigation = GeometryInsets(0, 0, 0, 72),
                density = 1.5f,
                densityDpi = 240,
            ),
        )

        assertEquals(GeometryRect(0, 45, 1920, 1128), result.snapWorkspace)
        assertEquals(GeometryInsets(0, 45, 0, 72), result.snapWorkspaceInsets)
        assertEquals(12, result.resolvedGapPx)
        // Native-validated horizontal edges: left task ends 954, right starts 966.
        assertEquals(954, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).right)
        assertEquals(966, result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT).left)
        // Full grid (vertical split is the DexZones symmetric policy).
        assertEquals(GeometryRect(0, 45, 954, 580), result.destinationQuadrants.getValue(Quadrant.TOP_LEFT))
        assertEquals(GeometryRect(966, 45, 1920, 580), result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT))
        assertEquals(GeometryRect(0, 592, 954, 1128), result.destinationQuadrants.getValue(Quadrant.BOTTOM_LEFT))
        assertEquals(GeometryRect(966, 592, 1920, 1128), result.destinationQuadrants.getValue(Quadrant.BOTTOM_RIGHT))
        assertDestinationInvariants(result)
    }

    // 4/6. Qreator external DeX shape at density 1.0.
    @Test
    fun `qreator shape reproduces native horizontal snap edges and grid quadrants`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 3840, 2160),
                navigation = GeometryInsets(0, 0, 0, 56),
                density = 1.0f,
                densityDpi = 160,
            ),
        )

        assertEquals(GeometryRect(0, 0, 3840, 2104), result.snapWorkspace)
        assertEquals(8, result.resolvedGapPx)
        assertEquals(1916, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).right)
        assertEquals(1924, result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT).left)
        assertEquals(GeometryRect(0, 0, 1916, 1048), result.destinationQuadrants.getValue(Quadrant.TOP_LEFT))
        assertEquals(GeometryRect(1924, 0, 3840, 1048), result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT))
        assertEquals(GeometryRect(0, 1056, 1916, 2104), result.destinationQuadrants.getValue(Quadrant.BOTTOM_LEFT))
        assertEquals(GeometryRect(1924, 1056, 3840, 2104), result.destinationQuadrants.getValue(Quadrant.BOTTOM_RIGHT))
        assertDestinationInvariants(result)
    }

    // --------------------------------------------------- workspace shapes

    @Test
    fun `left-side navigation bar shrinks from the left`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1600, 900),
                navigation = GeometryInsets(84, 0, 0, 0),
            ),
        )

        assertEquals(GeometryRect(84, 0, 1600, 900), result.snapWorkspace)
        assertEquals(84, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).left)
        assertDestinationInvariants(result)
    }

    @Test
    fun `right-side navigation bar shrinks from the right`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1600, 900),
                navigation = GeometryInsets(0, 0, 60, 0),
            ),
        )

        assertEquals(GeometryRect(0, 0, 1540, 900), result.snapWorkspace)
        assertEquals(1540, result.destinationQuadrants.getValue(Quadrant.BOTTOM_RIGHT).right)
        assertDestinationInvariants(result)
    }

    // 12. Non-zero origin.
    @Test
    fun `non-zero bounds origin is respected`() {
        val result = found(
            reading(
                bounds = GeometryRect(2000, 300, 3920, 1500),
                status = GeometryInsets(0, 40, 0, 0),
            ),
        )

        assertEquals(GeometryRect(2000, 340, 3920, 1500), result.snapWorkspace)
        assertEquals(GeometryRect(2000, 340, 2956, 916), result.destinationQuadrants.getValue(Quadrant.TOP_LEFT))
        assertDestinationInvariants(result)
    }

    // 13. Negative origin.
    @Test
    fun `negative origin virtual space is respected`() {
        val result = found(reading(bounds = GeometryRect(-50, -20, 950, 580)))

        assertEquals(GeometryRect(-50, -20, 950, 580), result.snapWorkspace)
        assertEquals(446, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).right)
        assertEquals(454, result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT).left)
        assertDestinationInvariants(result)
    }

    // 14. Asymmetric display insets on all four edges.
    @Test
    fun `asymmetric insets on all four edges`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1000, 800),
                navigation = GeometryInsets(17, 45, 23, 72),
            ),
        )

        assertEquals(GeometryRect(17, 45, 977, 728), result.snapWorkspace)
        assertDestinationInvariants(result)
    }

    @Test
    fun `zero insets use the full maximum bounds`() {
        val result = found(reading(bounds = GeometryRect(0, 0, 800, 600)))

        assertEquals(GeometryRect(0, 0, 800, 600), result.snapWorkspace)
        assertDestinationInvariants(result)
    }

    // ------------------------------------------------- odd-size behavior

    // 7. Odd workspace width plus even gap.
    @Test
    fun `odd width with even gap keeps the remainder on the right`() {
        val result = found(reading(bounds = GeometryRect(0, 0, 101, 100)))

        val tl = result.destinationQuadrants.getValue(Quadrant.TOP_LEFT)
        val tr = result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT)
        // midX = 50, gap 8 split 4/4: columns 46 and 47 wide.
        assertEquals(46L, tl.widthLong)
        assertEquals(47L, tr.widthLong)
        assertDestinationInvariants(result)
    }

    // 8. Odd workspace height plus even gap.
    @Test
    fun `odd height with even gap keeps the remainder on the bottom`() {
        val result = found(reading(bounds = GeometryRect(0, 0, 100, 101)))

        assertEquals(46L, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).heightLong)
        assertEquals(47L, result.destinationQuadrants.getValue(Quadrant.BOTTOM_LEFT).heightLong)
        assertDestinationInvariants(result)
    }

    @Test
    fun `both odd dimensions split deterministically`() {
        val result = found(reading(bounds = GeometryRect(0, 0, 101, 77)))

        assertEquals(GeometryRect(0, 0, 46, 34), result.destinationQuadrants.getValue(Quadrant.TOP_LEFT))
        assertEquals(GeometryRect(54, 42, 101, 77), result.destinationQuadrants.getValue(Quadrant.BOTTOM_RIGHT))
        assertDestinationInvariants(result)
    }

    // 9. Odd resolved pixel gap (8 dp * 1.375 = 11 px): the extra gap pixel
    // lands on the right/bottom side of the gap.
    @Test
    fun `odd resolved gap puts the extra pixel on the right and bottom of the gap`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 100, 100),
                density = 1.375f,
                densityDpi = 220,
            ),
        )

        assertEquals(11, result.resolvedGapPx)
        // midX = 50: gap occupies [45, 56) — 5 left of mid, 6 right.
        assertEquals(GeometryRect(0, 0, 45, 45), result.destinationQuadrants.getValue(Quadrant.TOP_LEFT))
        assertEquals(GeometryRect(56, 56, 100, 100), result.destinationQuadrants.getValue(Quadrant.BOTTOM_RIGHT))
        assertDestinationInvariants(result)
    }

    // ------------------------------------------------ invalid-input cases

    // 10. Gap too large for the workspace width.
    @Test
    fun `gap consuming the horizontal split is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(bounds = GeometryRect(0, 0, 8, 100)),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("horizontal grid gap"))
    }

    @Test
    fun `gap consuming the vertical split is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(bounds = GeometryRect(0, 0, 100, 8)),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("vertical grid gap"))
    }

    // 11. Tiny workspace with gap.
    @Test
    fun `tiny workspace with gap is invalid rather than degenerate`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 4, 4),
                navigation = GeometryInsets(1, 1, 1, 1),
            ),
        )

        assertTrue(result is DesktopWorkAreaAssessment.Invalid)
    }

    @Test
    fun `insets consuming all width are invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 100, 600),
                navigation = GeometryInsets(60, 0, 60, 0),
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("horizontal"))
    }

    @Test
    fun `insets consuming all height are invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 800, 100),
                navigation = GeometryInsets(0, 50, 0, 50),
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("vertical"))
    }

    @Test
    fun `empty maximum bounds are invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(bounds = GeometryRect(100, 100, 100, 200)),
        )

        assertTrue(result is DesktopWorkAreaAssessment.Invalid)
    }

    // 18. Density must be finite and positive — consistently Invalid.
    @Test
    fun `invalid density values are rejected consistently`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach { badDensity ->
            val result = WorkspaceGeometryCalculator.calculate(
                reading(bounds = GeometryRect(0, 0, 1000, 800), density = badDensity),
            )
            val invalid = result as DesktopWorkAreaAssessment.Invalid
            assertTrue("was: ${invalid.reason}", invalid.reason.contains("density"))
        }
    }

    // ------------------------------------------- component-inset integrity

    @Test
    fun `negative status-bar component edge is invalid despite clean system bars`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 1920, 1200),
                status = GeometryInsets(-3, 0, 0, 0), // left edge
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("status-bar"))
    }

    @Test
    fun `negative navigation-bar component edge is invalid despite clean system bars`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 1920, 1200),
                navigation = GeometryInsets(0, 0, -7, 0), // right edge
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("navigation-bar"))
    }

    @Test
    fun `negative caption-bar component edge is invalid despite clean system bars`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 1920, 1200),
                caption = GeometryInsets(0, 0, 0, -1), // bottom edge
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("caption-bar"))
    }

    @Test
    fun `negative system-bar component edge is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 1000, 600),
                system = GeometryInsets(0, -10, 0, 0),
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("system-bar"))
    }

    @Test
    fun `negative system-overlay component edge is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 1920, 1200),
                overlay = GeometryInsets(0, 0, 0, -4),
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("system-overlay"))
    }

    @Test
    fun `negative cutout component edge is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, 0, 1920, 1200),
                cutout = GeometryInsets(0, -2, 0, 0),
            ),
        )

        val invalid = result as DesktopWorkAreaAssessment.Invalid
        assertTrue(invalid.reason.contains("display-cutout"))
    }

    // ------------------------------------------------------ mask semantics

    @Test
    fun `cutout overlapping a system bar is unioned not summed in the snap mask`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1000, 600),
                status = GeometryInsets(0, 40, 0, 0),
                cutout = GeometryInsets(0, 55, 0, 0),
            ),
        )

        assertEquals(GeometryInsets(0, 55, 0, 0), result.snapWorkspaceInsets)
        assertEquals(55, result.snapWorkspace.top)
    }

    @Test
    fun `cutout on an uninset edge extends the snap mask to that edge`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1000, 600),
                status = GeometryInsets(0, 40, 0, 0),
                cutout = GeometryInsets(30, 0, 0, 0),
            ),
        )

        assertEquals(GeometryInsets(30, 40, 0, 0), result.snapWorkspaceInsets)
    }

    // BLOCKING-defect regression: the platform's raw systemBars value
    // INCLUDES the caption bar. The caption contribution present inside raw
    // systemBars must not leak into the snap workspace — only the
    // individual display-level components (status/navigation/overlay/
    // cutout) are applied.
    @Test
    fun `caption inside raw system bars does not leak into the snap workspace`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1000, 800),
                status = GeometryInsets.NONE,
                navigation = GeometryInsets(0, 0, 0, 50),
                caption = GeometryInsets(0, 60, 0, 0),
                // Realistic raw combined value: caption top 60 + nav bottom 50.
                system = GeometryInsets(0, 60, 0, 50),
            ),
        )

        // Caption's 60 px top contribution is diagnostic only.
        assertEquals(GeometryRect(0, 0, 1000, 750), result.snapWorkspace)
        assertNotEquals(GeometryRect(0, 60, 1000, 750), result.snapWorkspace)
        assertEquals(GeometryInsets(0, 0, 0, 50), result.snapWorkspaceInsets)
        // Raw evidence remains visible and untouched.
        assertEquals(GeometryInsets(0, 60, 0, 50), result.reading.systemBarInsets)
        assertEquals(GeometryInsets(0, 60, 0, 0), result.reading.captionBarInsets)
        assertDestinationInvariants(result)
    }

    @Test
    fun `system overlays participate in the snap workspace mask`() {
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1000, 800),
                overlay = GeometryInsets(30, 0, 0, 0),
            ),
        )

        assertEquals(GeometryInsets(30, 0, 0, 0), result.snapWorkspaceInsets)
        assertEquals(30, result.snapWorkspace.left)
        assertDestinationInvariants(result)
    }

    @Test
    fun `component insets are retained separately for diagnostics`() {
        val status = GeometryInsets(0, 45, 0, 0)
        val navigation = GeometryInsets(0, 0, 0, 72)
        val caption = GeometryInsets(0, 12, 0, 0)
        val result = found(
            reading(
                bounds = GeometryRect(0, 0, 1920, 1200),
                status = status,
                navigation = navigation,
                caption = caption,
            ),
        )

        assertEquals(status, result.reading.statusBarInsets)
        assertEquals(navigation, result.reading.navigationBarInsets)
        assertEquals(caption, result.reading.captionBarInsets)
    }

    // ---------------------------------------------------- multi-display

    @Test
    fun `displays are calculated independently`() {
        val tablet = found(
            reading(
                displayId = 0,
                bounds = GeometryRect(0, 0, 1920, 1200),
                status = GeometryInsets(0, 45, 0, 0),
                navigation = GeometryInsets(0, 0, 0, 72),
                density = 1.5f,
            ),
        )
        val monitor = found(
            reading(
                displayId = 14,
                bounds = GeometryRect(0, 0, 3840, 2160),
                navigation = GeometryInsets(0, 0, 0, 56),
                density = 1.0f,
            ),
        )

        assertEquals(0, tablet.displayId)
        assertEquals(14, monitor.displayId)
        assertNotEquals(tablet.snapWorkspace, monitor.snapWorkspace)
        assertNotEquals(tablet.resolvedGapPx, monitor.resolvedGapPx)
        assertDestinationInvariants(tablet)
        assertDestinationInvariants(monitor)
    }

    @Test
    fun `display id is a correlation key and never a geometry input`() {
        fun forDisplay(id: Int) = found(
            reading(
                displayId = id,
                bounds = GeometryRect(0, 0, 1920, 1200),
                status = GeometryInsets(0, 45, 0, 0),
                navigation = GeometryInsets(0, 0, 0, 72),
                density = 1.5f,
            ),
        )

        val ids = listOf(0, 5, 14, 999999, Int.MAX_VALUE)
        val results = ids.map { forDisplay(it) }
        results.forEach { result ->
            assertEquals(results.first().snapWorkspace, result.snapWorkspace)
            assertEquals(results.first().destinationQuadrants, result.destinationQuadrants)
            assertEquals(results.first().snapWorkspaceInsets, result.snapWorkspaceInsets)
            assertEquals(results.first().resolvedGapPx, result.resolvedGapPx)
        }
    }

    @Test
    fun `arbitrary unusual dimensions follow the same formula with no constants`() {
        val result = found(
            reading(
                bounds = GeometryRect(13, 29, 1247, 806),
                navigation = GeometryInsets(3, 11, 5, 7),
            ),
        )

        val work = result.snapWorkspace
        assertEquals(GeometryRect(16, 40, 1242, 799), work)
        val midX = (work.left + work.widthLong / 2).toInt()
        val gap = result.resolvedGapPx
        assertEquals(midX - gap / 2, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).right)
        assertEquals(midX + gap - gap / 2, result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT).left)
        assertDestinationInvariants(result)
    }

    @Test
    fun `destination invariants hold across a sweep of shapes`() {
        val shapes = listOf(
            GeometryRect(0, 0, 640, 480),
            GeometryRect(0, 0, 1337, 999),
            GeometryRect(5, 7, 1234, 777),
            GeometryRect(100, 100, 120, 115),
            GeometryRect(-50, -20, 950, 580),
        )
        shapes.forEach { bounds ->
            val result = found(reading(bounds = bounds))
            assertDestinationInvariants(result)
        }
    }

    // ------------------------------------------- overflow/underflow safety

    @Test
    fun `full int-range bounds are handled safely without wrapping`() {
        val result = found(
            reading(
                bounds = GeometryRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            ),
        )

        // Midpoint of the full span is -1 (floor division); gap 8 splits 4/4.
        assertEquals(-5, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).right)
        assertEquals(3, result.destinationQuadrants.getValue(Quadrant.TOP_RIGHT).left)
        assertDestinationInvariants(result)
    }

    @Test
    fun `left inset pushing past int max is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(Int.MAX_VALUE - 10, 0, Int.MAX_VALUE, 100),
                navigation = GeometryInsets(100, 0, 0, 0),
            ),
        )

        assertTrue("was: $result", result is DesktopWorkAreaAssessment.Invalid)
    }

    @Test
    fun `top inset pushing past int max is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, Int.MAX_VALUE - 10, 100, Int.MAX_VALUE),
                navigation = GeometryInsets(0, 100, 0, 0),
            ),
        )

        assertTrue("was: $result", result is DesktopWorkAreaAssessment.Invalid)
    }

    @Test
    fun `right inset pushing below int min is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(Int.MIN_VALUE, 0, Int.MIN_VALUE + 10, 100),
                navigation = GeometryInsets(0, 0, 100, 0),
            ),
        )

        assertTrue("was: $result", result is DesktopWorkAreaAssessment.Invalid)
    }

    @Test
    fun `bottom inset pushing below int min is invalid`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(0, Int.MIN_VALUE, 100, Int.MIN_VALUE + 10),
                navigation = GeometryInsets(0, 0, 0, 100),
            ),
        )

        assertTrue("was: $result", result is DesktopWorkAreaAssessment.Invalid)
    }

    @Test
    fun `extreme insets that would wrap int arithmetic fail closed`() {
        val result = WorkspaceGeometryCalculator.calculate(
            reading(
                bounds = GeometryRect(1000, 1000, 2000, 2000),
                navigation = GeometryInsets(Int.MAX_VALUE, 0, 0, 0),
            ),
        )

        assertTrue("was: $result", result is DesktopWorkAreaAssessment.Invalid)
    }

    @Test
    fun `midpoint near positive int boundary is exact`() {
        val result = found(
            reading(
                bounds = GeometryRect(Int.MAX_VALUE - 100, Int.MAX_VALUE - 100, Int.MAX_VALUE, Int.MAX_VALUE),
            ),
        )

        // midX = MAX-50; gap 8 -> left column ends at MAX-54.
        assertEquals(Int.MAX_VALUE - 54, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).right)
        assertDestinationInvariants(result)
    }

    @Test
    fun `midpoint near negative int boundary is exact`() {
        val result = found(
            reading(
                bounds = GeometryRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE + 100, Int.MIN_VALUE + 100),
            ),
        )

        // midX = MIN+50; gap 8 -> left column ends at MIN+46.
        assertEquals(Int.MIN_VALUE + 46, result.destinationQuadrants.getValue(Quadrant.TOP_LEFT).right)
        assertDestinationInvariants(result)
    }

    @Test
    fun `full-range rectangle spans are reported safely via long helpers`() {
        val full = GeometryRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)

        assertEquals(4294967295L, full.widthLong)
        assertEquals(4294967295L, full.heightLong)
        assertTrue(!full.isEmpty)
    }
}

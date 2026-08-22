package uk.mr_biz.fourzones.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceInterpreterTest {

    private val fullscreenBounds = BoundsSnapshot(0, 0, 1080, 2340)
    private val halfScreenBounds = BoundsSnapshot(0, 0, 1080, 1170)
    private val floatingBounds = BoundsSnapshot(100, 200, 900, 800)
    private val pipBounds = BoundsSnapshot(700, 1800, 1060, 2000)

    private val noInsets = InsetValues(0, 0, 0, 0)

    private fun state(
        hostingDisplayId: Int? = 0,
        isInMultiWindowMode: Boolean = false,
        isInPictureInPictureMode: Boolean = false,
        supportsFreeformWindowManagement: Boolean = false,
        uiModeType: UiModeType = UiModeType.NORMAL,
        currentBounds: BoundsSnapshot = fullscreenBounds,
        maximumBounds: BoundsSnapshot = fullscreenBounds,
    ) = WorkspaceState(
        hostingDisplayId = hostingDisplayId,
        isInMultiWindowMode = isInMultiWindowMode,
        isInPictureInPictureMode = isInPictureInPictureMode,
        supportsFreeformWindowManagement = supportsFreeformWindowManagement,
        uiModeType = uiModeType,
        currentBounds = currentBounds,
        maximumBounds = maximumBounds,
        insets = WorkspaceInsets(noInsets, noInsets, noInsets, noInsets),
        configuration = ConfigurationSnapshot(
            screenWidthDp = 411,
            screenHeightDp = 891,
            smallestScreenWidthDp = 411,
            orientation = ScreenOrientation.PORTRAIT,
        ),
    )

    /** No evidence line may ever claim an active desktop/DeX workspace. */
    private fun assertNoDesktopClaim(interpretation: WorkspaceInterpretation) {
        interpretation.evidence.forEach { line ->
            assertFalse(
                "Evidence line must not claim active desktop: \"$line\"",
                line.contains("desktop mode is active", ignoreCase = true) ||
                    line.contains("desktop detected", ignoreCase = true) ||
                    line.contains("DeX detected", ignoreCase = true) ||
                    line.contains("proves desktop", ignoreCase = true),
            )
        }
    }

    @Test
    fun `assessment model has no ACTIVE_DESKTOP verdict at all`() {
        // The strongest expressible verdict is EVIDENCE_PRESENT; an active-
        // desktop claim cannot be produced because it cannot be represented.
        assertEquals(
            setOf(WorkspaceAssessment.EVIDENCE_PRESENT, WorkspaceAssessment.UNDETERMINED),
            WorkspaceAssessment.entries.toSet(),
        )
    }

    @Test
    fun `plain fullscreen activity is UNDETERMINED`() {
        val result = WorkspaceInterpreter.interpret(state())

        assertEquals(WorkspaceAssessment.UNDETERMINED, result.assessment)
        assertTrue(result.evidence.any { it.contains("No observed public signal") })
        assertNoDesktopClaim(result)
    }

    @Test
    fun `multi-window alone is evidence but never desktop mode`() {
        val result = WorkspaceInterpreter.interpret(state(isInMultiWindowMode = true))

        assertEquals(WorkspaceAssessment.EVIDENCE_PRESENT, result.assessment)
        assertTrue(result.evidence.any { it.contains("split-screen is also multi-window") })
        assertNoDesktopClaim(result)
    }

    @Test
    fun `freeform feature alone is capability not current-state evidence`() {
        val result = WorkspaceInterpreter.interpret(state(supportsFreeformWindowManagement = true))

        // Capability contributes nothing to the current-mode assessment.
        assertEquals(WorkspaceAssessment.UNDETERMINED, result.assessment)
        assertTrue(result.evidence.any { it.contains("device capability only") })
        assertNoDesktopClaim(result)
    }

    @Test
    fun `freeform capability and multi-window state stay separate`() {
        val capabilityOnly = WorkspaceInterpreter.interpret(state(supportsFreeformWindowManagement = true))
        val multiWindowOnly = WorkspaceInterpreter.interpret(state(isInMultiWindowMode = true))

        // Capability alone: no current-mode evidence. Multi-window alone:
        // evidence exists with or without the freeform capability, so neither
        // signal is derived from or implied by the other.
        assertEquals(WorkspaceAssessment.UNDETERMINED, capabilityOnly.assessment)
        assertEquals(WorkspaceAssessment.EVIDENCE_PRESENT, multiWindowOnly.assessment)

        val both = WorkspaceInterpreter.interpret(
            state(supportsFreeformWindowManagement = true, isInMultiWindowMode = true),
        )
        assertEquals(WorkspaceAssessment.EVIDENCE_PRESENT, both.assessment)
        assertTrue(both.evidence.any { it.contains("device capability only") })
        assertTrue(both.evidence.any { it.contains("split-screen is also multi-window") })
        assertNoDesktopClaim(both)
    }

    @Test
    fun `current bounds smaller than maximum is evidence but not proof`() {
        val result = WorkspaceInterpreter.interpret(
            state(currentBounds = floatingBounds, maximumBounds = fullscreenBounds),
        )

        assertEquals(WorkspaceAssessment.EVIDENCE_PRESENT, result.assessment)
        assertTrue(result.evidence.any { it.contains("not proof of desktop mode") })
        assertNoDesktopClaim(result)
    }

    @Test
    fun `desk ui mode is an explicit signal but not equated with Samsung DeX`() {
        val result = WorkspaceInterpreter.interpret(state(uiModeType = UiModeType.DESK))

        assertEquals(WorkspaceAssessment.EVIDENCE_PRESENT, result.assessment)
        assertTrue(result.evidence.any { it.contains("UI_MODE_TYPE_DESK") })
        assertTrue(result.evidence.any { it.contains("not equated with Samsung DeX") })
        assertNoDesktopClaim(result)
    }

    @Test
    fun `pip explains multi-window and reduced bounds so both are discounted`() {
        val result = WorkspaceInterpreter.interpret(
            state(
                isInMultiWindowMode = true,
                isInPictureInPictureMode = true,
                currentBounds = pipBounds,
                maximumBounds = fullscreenBounds,
            ),
        )

        assertEquals(WorkspaceAssessment.UNDETERMINED, result.assessment)
        assertTrue(result.evidence.any { it.contains("picture-in-picture") })
        assertNoDesktopClaim(result)
    }

    @Test
    fun `split-screen-like signals are not mislabeled as desktop`() {
        // Split screen on a freeform-capable phone: multi-window, half-height
        // window, freeform capability present, UI mode NORMAL.
        val result = WorkspaceInterpreter.interpret(
            state(
                isInMultiWindowMode = true,
                supportsFreeformWindowManagement = true,
                currentBounds = halfScreenBounds,
                maximumBounds = fullscreenBounds,
            ),
        )

        // Evidence is present — but every line carries its limits and none
        // claims desktop, and the verdict cannot exceed EVIDENCE_PRESENT.
        assertEquals(WorkspaceAssessment.EVIDENCE_PRESENT, result.assessment)
        assertTrue(result.evidence.any { it.contains("split-screen is also multi-window") })
        assertTrue(result.evidence.any { it.contains("not proof of desktop mode") })
        assertNoDesktopClaim(result)
    }

    @Test
    fun `hosting display id never affects interpretation`() {
        val ids = listOf(null, 0, 1, 15, 4711, Int.MAX_VALUE)
        val interpretations = ids.map { id ->
            WorkspaceInterpreter.interpret(
                state(
                    hostingDisplayId = id,
                    isInMultiWindowMode = true,
                    supportsFreeformWindowManagement = true,
                ),
            )
        }

        interpretations.forEach { assertEquals(interpretations.first(), it) }
    }

    @Test
    fun `currentDiffersFromMaximum reflects bounds equality`() {
        assertFalse(state().currentDiffersFromMaximum)
        assertTrue(
            state(currentBounds = halfScreenBounds, maximumBounds = fullscreenBounds)
                .currentDiffersFromMaximum,
        )
        // Same size at a different position still differs: position is part
        // of the window bounds, not just dimensions.
        val shifted = BoundsSnapshot(10, 10, 1090, 2350)
        assertTrue(
            state(currentBounds = shifted, maximumBounds = fullscreenBounds)
                .currentDiffersFromMaximum,
        )
    }
}

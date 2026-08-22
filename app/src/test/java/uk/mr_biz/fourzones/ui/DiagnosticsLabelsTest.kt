package uk.mr_biz.fourzones.ui

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLabelsTest {

    @Test
    fun `activity and shortcut backends are represented independently`() {
        // Distinct contexts and distinct value lines, so the two lifecycles can
        // never be mistaken for each other in diagnostics.
        assertNotEquals(
            DiagnosticsLabels.ACTIVITY_BACKEND_CONTEXT,
            DiagnosticsLabels.SHORTCUT_BACKEND_CONTEXT,
        )
        assertTrue(DiagnosticsLabels.ACTIVITY_BACKEND_CONTEXT.contains("Activity backend"))
        assertTrue(DiagnosticsLabels.SHORTCUT_BACKEND_CONTEXT.contains("Shortcut backend"))

        val activity = DiagnosticsLabels.activityBackendLabel(PrivilegedBackendStatus.READY)
        val shortcut = DiagnosticsLabels.shortcutBackendLabel(PrivilegedBackendStatus.READY)
        assertEquals("Activity backend: READY", activity)
        assertEquals("Shortcut backend: READY", shortcut)
        assertNotEquals(activity, shortcut)
    }

    @Test
    fun `null shortcut backend is distinguishable from READY and never reads READY`() {
        val nullLabel = DiagnosticsLabels.shortcutBackendLabel(null)
        assertEquals("Shortcut backend: no current observation", nullLabel)
        assertFalse(nullLabel.contains("READY"))
        assertNotEquals(
            DiagnosticsLabels.shortcutBackendLabel(PrivilegedBackendStatus.READY),
            nullLabel,
        )
    }

    @Test
    fun `null activity backend reads not started`() {
        assertEquals("Activity backend: not started", DiagnosticsLabels.activityBackendLabel(null))
    }

    @Test
    fun `shortcut backend exposes the raw enum name for every status`() {
        // Diagnostics show the raw truth (never the ProductReadiness mapping).
        PrivilegedBackendStatus.entries.forEach { status ->
            assertEquals(
                "Shortcut backend: ${status.name}",
                DiagnosticsLabels.shortcutBackendLabel(status),
            )
        }
    }

    @Test
    fun `no stale reception-only wording remains in section titles`() {
        listOf(
            DiagnosticsLabels.SCREEN_HEADING,
            DiagnosticsLabels.SCREEN_SUBTEXT,
            DiagnosticsLabels.SECTION_SHORTCUTS,
        ).forEach { assertFalse("stale wording in \"$it\"", it.contains("reception only")) }
        assertEquals("Global keyboard shortcuts", DiagnosticsLabels.SECTION_SHORTCUTS)
        assertEquals("Developer diagnostics", DiagnosticsLabels.SCREEN_HEADING)
    }
}

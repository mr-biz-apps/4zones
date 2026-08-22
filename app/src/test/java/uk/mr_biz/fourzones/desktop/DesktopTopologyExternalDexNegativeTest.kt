package uk.mr_biz.fourzones.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S25 name-based fallback must be CONSERVATIVE: only a top-level,
 * organizer-created, freeform, exactly-"Desk" root is an activatable desktop,
 * and only the force-hidden "MinimizedDesk_" form is a minimized root. These
 * tests prove nothing else is mistaken for a desk root, and that the fallback
 * never overrides or fires alongside the authoritative A9 markers.
 */
class DesktopTopologyExternalDexNegativeTest {

    private fun singleRootDump(header: String, properties: String = ""): String =
        buildString {
            appendLine("Display #2 (activities from top to bottom):")
            appendLine("  * $header")
            properties.lineSequence().filter { it.isNotBlank() }.forEach {
                appendLine("    ${it.trim()}")
            }
        }

    private fun parseRoots(header: String, properties: String = "") =
        DesktopTopologyParser.parse(singleRootDump(header, properties)).roots

    @Test
    fun `name=Desk without organizer is not a desktop`() {
        val roots = parseRoots(
            "Task{x #700 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}",
        )
        assertTrue(roots.isEmpty())
    }

    @Test
    fun `organizer Desk that is not freeform is not a desktop`() {
        val roots = parseRoots(
            "Task{x #700 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=fullscreen translucent=true sz=0}",
            "mCreatedByOrganizer=true",
        )
        assertTrue(roots.isEmpty())
    }

    @Test
    fun `ordinary freeform application task is not a desktop`() {
        val roots = parseRoots(
            "Task{x #700 type=standard A=10100:com.example.app name=Chrome U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}",
            "mCreatedByOrganizer=false",
        )
        assertTrue(roots.isEmpty())
    }

    @Test
    fun `organizer SplitRoot is not a desktop`() {
        val roots = parseRoots(
            "Task{x #700 type=undefined name=SplitRoot U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}",
            "mCreatedByOrganizer=true",
        )
        assertTrue(roots.isEmpty())
    }

    @Test
    fun `a name merely containing Desk is not a desktop`() {
        // "MyDesk" is not exactly "Desk" and does not begin "MinimizedDesk_".
        val roots = parseRoots(
            "Task{x #700 type=undefined name=MyDesk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}",
            "mCreatedByOrganizer=true",
        )
        assertTrue(roots.isEmpty())
    }

    @Test
    fun `MinimizedDesk-like name without organizer or force-hidden is not minimized`() {
        // Organizer present but NOT force-hidden.
        val notHidden = parseRoots(
            "Task{x #700 type=undefined name=MinimizedDesk_abc U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}",
            "mCreatedByOrganizer=true",
        )
        assertTrue(notHidden.isEmpty())

        // Force-hidden and visible=false but NOT organizer-created.
        val notOrganizer = parseRoots(
            "Task{x #700 type=undefined name=MinimizedDesk_abc U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}",
            "isForceHidden=true",
        )
        assertTrue(notOrganizer.isEmpty())
    }

    @Test
    fun `two visible qualified Desk roots are ambiguous`() {
        val dump = """
            Display #2 (activities from top to bottom):
              * Task{a #700 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}
                mCreatedByOrganizer=true
              * Task{b #710 type=undefined name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}
                mCreatedByOrganizer=true
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)
        assertEquals(
            ActiveDesktopAssessment.Ambiguous(listOf(700, 710)),
            snapshot.activeDesktopByDisplay[2],
        )
    }

    @Test
    fun `no visible qualified Desk root is conservative none`() {
        val dump = """
            Display #2 (activities from top to bottom):
              * Task{a #700 type=undefined name=Desk U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}
                mCreatedByOrganizer=true
              * Task{b #710 type=undefined name=Desk U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}
                mCreatedByOrganizer=true
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)
        assertEquals(ActiveDesktopAssessment.None, snapshot.activeDesktopByDisplay[2])
    }

    @Test
    fun `explicit A9 marker keeps precedence over a coincident name=Desk header`() {
        // A root carrying BOTH an explicit dw=activatable AND name=Desk must be
        // driven by the authoritative explicit marker, not reinterpreted.
        val explicitActivatable = parseRoots(
            "Task{x #700 type=undefined dw=activatable name=Desk U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}",
        )
        assertEquals(DeskRootType.ACTIVATABLE, explicitActivatable.single().type)

        // Explicit dw=minimized wins even though the name says Desk and it is
        // not force-hidden (the fallback would otherwise reject it).
        val explicitMinimized = parseRoots(
            "Task{x #700 type=undefined dw=minimized name=Desk U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}",
            "mCreatedByOrganizer=true",
        )
        assertEquals(DeskRootType.MINIMIZED, explicitMinimized.single().type)
    }
}

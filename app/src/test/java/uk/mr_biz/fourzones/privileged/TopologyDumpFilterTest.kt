package uk.mr_biz.fourzones.privileged

import uk.mr_biz.fourzones.desktop.ActiveDesktopAssessment
import uk.mr_biz.fourzones.desktop.DeskRootType
import uk.mr_biz.fourzones.desktop.DesktopTopologyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyDumpFilterTest {

    private val syntheticDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{aa #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
            mCreatedByOrganizer=true
            mDeskRootTaskType=activatable
            * Task{bb #17 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
              mBounds=Rect(441, 196 - 1479, 976)
              * Hist  #0: ActivityRecord{cc u0 com.example.app/.Main t17}
                packageName=com.example.app processName=com.example.app
                Intent { act=android.intent.action.MAIN cmp=com.example.app/.Main }
                baseDir=/data/app/example/base.apk
          * Task{dd #808 type=undefined dw=minimized U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}
            mDeskRootTaskType=minimized
            isForceHidden=true
            isSleeping=false
        ActivityTaskSupervisor state:
          topDisplayFocusedRootTask=Task{aa #310 type=undefined dw=activatable}
          mFocusedApp=ActivityRecord{cc u0 com.example.app/.Main t17}
          mWallpaperTarget=Window{w u0 com.example.wallpaper}
    """.trimIndent()

    @Test
    fun `keeps allowlisted structural lines and drops unrelated dump content`() {
        val filtered = TopologyDumpFilter.filter(syntheticDump)

        assertTrue(filtered.contains("Display #0"))
        assertTrue(filtered.contains("* Task{aa #310"))
        assertTrue(filtered.contains("mDeskRootTaskType=activatable"))
        assertTrue(filtered.contains("mCreatedByOrganizer=true"))
        assertTrue(filtered.contains("isForceHidden=true"))
        assertTrue(filtered.contains("* Hist "))
        assertTrue(filtered.contains("mFocusedApp="))
        assertTrue(filtered.contains("ActivityTaskSupervisor state:"))
        // Task outer bounds are now retained for Phase 2C3A postcondition
        // verification (the standalone task-level mBounds line only).
        assertTrue(filtered.contains("mBounds=Rect(441, 196 - 1479, 976)"))

        // Data minimization: intents, paths and other unrelated content
        // never leave the privileged process.
        assertFalse(filtered.contains("Intent {"))
        assertFalse(filtered.contains("baseDir="))
        assertFalse(filtered.contains("isSleeping"))
        assertFalse(filtered.contains("mWallpaperTarget"))
        // Not needed by the parser, so no longer retained.
        assertFalse(filtered.contains("topDisplayFocusedRootTask"))
        assertFalse(filtered.contains("ACTIVITY MANAGER"))
    }

    @Test
    fun `only the standalone bounds rectangle form is retained`() {
        val input = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(10, 20 - 30, 40)
                  mBounds=Rect(10, 20 - 30, 40) trailing junk
                  mBoundsSomethingElse=Rect(1, 2 - 3, 4)
        """.trimIndent()

        val filtered = TopologyDumpFilter.filter(input)

        // Exactly one bounds line survives — the anchored standalone form.
        assertEquals(1, filtered.lines().count { it.trim().startsWith("mBounds=Rect(") })
        assertTrue(filtered.contains("mBounds=Rect(10, 20 - 30, 40)"))
        assertFalse(filtered.contains("trailing junk"))
        assertFalse(filtered.contains("mBoundsSomethingElse"))
    }

    @Test
    fun `arbitrary unindented text is discarded not kept`() {
        val hostile = """
            RANDOM UNKNOWN HEADING:
            Task{evil #666 type=undefined dw=activatable U=0 visible=true}
            mDeskRootTaskType=activatable
            ERROR: something failed
            /data/app/some/path.apk
            Display of nonsense
        """.trimIndent()

        assertEquals("", TopologyDumpFilter.filter(hostile))
    }

    @Test
    fun `required section boundaries survive`() {
        val filtered = TopologyDumpFilter.filter(syntheticDump)
        val lines = filtered.lines().filter { it.isNotEmpty() }

        assertEquals("Display #0 (activities from top to bottom):", lines.first())
        assertTrue(lines.contains("ActivityTaskSupervisor state:"))
    }

    @Test
    fun `preserves order and indentation so filtered output stays parseable`() {
        val filtered = TopologyDumpFilter.filter(syntheticDump)
        val lines = filtered.lines()

        val rootIndex = lines.indexOfFirst { it.contains("#310") }
        val childIndex = lines.indexOfFirst { it.contains("#17 ") }
        assertTrue(rootIndex in 0 until childIndex)
        assertTrue(lines[rootIndex].startsWith("  * Task{"))
        assertTrue(lines[childIndex].startsWith("    * Task{"))
    }

    @Test
    fun `task lines after an unknown discarded heading are discarded too`() {
        // The unknown heading closes the display region even though the
        // heading itself is dropped, so the task-shaped echoes under it can
        // never masquerade as topology.
        val input = """
            Display #0 (activities from top to bottom):
              * Task{aa #21 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}
                mDeskRootTaskType=activatable
            SOME UNKNOWN SECTION:
              * Task{zz #4141 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}
                mDeskRootTaskType=activatable
        """.trimIndent()

        val filtered = TopologyDumpFilter.filter(input)

        assertTrue(filtered.contains("#21"))
        assertFalse(filtered.contains("#4141"))
        assertFalse(filtered.contains("SOME UNKNOWN SECTION"))
        // End to end: the echo cannot create a second candidate.
        val snapshot = DesktopTopologyParser.parse(filtered)
        assertEquals(ActiveDesktopAssessment.Found(21), snapshot.activeDesktopByDisplay[0])
    }

    @Test
    fun `malformed unknown top-level text cannot become supported topology`() {
        val hostile = """
            NOT A DISPLAY HEADER dw=activatable mDeskRootTaskType=activatable
              * Task{aa #77 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=0}
                mDeskRootTaskType=activatable
        """.trimIndent()

        val filtered = TopologyDumpFilter.filter(hostile)

        // No display region was ever opened, so nothing is retained and the
        // parser conservatively reports no supported topology at all.
        assertEquals("", filtered)
        assertTrue(DesktopTopologyParser.parse(filtered).activeDesktopByDisplay.isEmpty())
    }

    @Test
    fun `sanitized four-desktop fixture still parses correctly after filtering`() {
        val fixture = buildString {
            appendLine("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)")
            appendLine("Display #0 (activities from top to bottom):")
            // Active desktop with one app task.
            appendLine("  * Task{a #501 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("    mCreatedByOrganizer=true")
            appendLine("    mDeskRootTaskType=activatable")
            appendLine("    * Task{a1 #90 type=standard A=10001:com.example.one U=0 rootTaskId=501 visible=true visibleRequested=true mode=freeform translucent=true sz=1}")
            appendLine("      * Hist  #0: ActivityRecord{h1 u0 com.example.one/.Main t90}")
            // Three inactive activatable desktops, one with multiple tasks.
            appendLine("  * Task{b #7002 type=undefined dw=activatable U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}")
            appendLine("    mDeskRootTaskType=activatable")
            appendLine("    * Task{b1 #12 type=standard A=10002:com.example.two U=0 rootTaskId=7002 visible=false visibleRequested=false mode=freeform translucent=true sz=1}")
            appendLine("  * Task{c #43 type=undefined dw=activatable U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}")
            appendLine("    mDeskRootTaskType=activatable")
            appendLine("    * Task{c1 #888888 type=standard A=10003:com.example.three U=0 rootTaskId=43 visible=false visibleRequested=false mode=freeform translucent=true sz=1}")
            appendLine("  * Task{d #6100 type=undefined dw=activatable U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=3}")
            appendLine("    mDeskRootTaskType=activatable")
            appendLine("    * Task{d1 #300 type=standard A=10004:com.example.four U=0 rootTaskId=6100 visible=false visibleRequested=false mode=freeform translucent=true sz=1}")
            appendLine("    * Task{d2 #301 type=standard A=10005:com.example.five U=0 rootTaskId=6100 visible=false visibleRequested=false mode=freeform translucent=true sz=1}")
            appendLine("    * Task{d3 #302 type=standard A=10006:com.example.six U=0 rootTaskId=6100 visible=false visibleRequested=false mode=freeform translucent=true sz=1}")
            // Four minimized desk roots.
            listOf(15, 9800, 2, 77070).forEach { id ->
                appendLine("  * Task{m$id #$id type=undefined dw=minimized U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=0}")
                appendLine("    mDeskRootTaskType=minimized")
                appendLine("    isForceHidden=true")
            }
            // Unrelated noise the filter must strip.
            appendLine("      Intent { act=android.intent.action.MAIN cmp=com.example.one/.Main }")
            appendLine("ActivityTaskSupervisor state:")
            appendLine("  mFocusedApp=ActivityRecord{h1 u0 com.example.one/.Main t90}")
        }

        val snapshot = DesktopTopologyParser.parse(TopologyDumpFilter.filter(fixture))

        assertEquals(4, snapshot.activatableRoots.size)
        assertEquals(4, snapshot.minimizedRoots.size)
        assertEquals(ActiveDesktopAssessment.Found(501), snapshot.activeDesktopByDisplay[0])
        assertEquals(90, snapshot.focusedTaskId)
        assertEquals(
            3,
            snapshot.roots.single { it.rootTaskId == 6100 }.childTasks.size,
        )
        assertTrue(snapshot.minimizedRoots.all { it.type == DeskRootType.MINIMIZED })
    }

    @Test
    fun `empty and garbage input do not crash`() {
        assertEquals("", TopologyDumpFilter.filter(""))
        assertEquals("", TopologyDumpFilter.filter("   \n  random noise\n\t\n"))
    }

    @Test
    fun `output below the limit succeeds`() {
        val comfortable = buildString {
            appendLine("Display #0 (activities from top to bottom):")
            repeat(100) { i ->
                appendLine(
                    "  * Task{t$i #${1000 + i} type=undefined dw=minimized U=0 visible=false " +
                        "visibleRequested=false mode=freeform translucent=true sz=0}",
                )
            }
        }

        val filtered = TopologyDumpFilter.filter(comfortable)

        assertTrue(filtered.isNotEmpty())
        assertTrue(filtered.length <= TopologyDumpFilter.MAX_OUTPUT_CHARS)
    }

    @Test
    fun `output exceeding the limit fails closed instead of truncating`() {
        // An apparently valid, visible activatable desktop appears FIRST, so
        // a truncating filter would hand the parser a prefix that yields
        // FOUND — while a second visible activatable root after the cutoff
        // should have made it AMBIGUOUS. Failing closed means the parser
        // never sees any of it: no partial snapshot, no FOUND.
        val oversized = buildString {
            appendLine("Display #0 (activities from top to bottom):")
            appendLine(
                "  * Task{first #333 type=undefined dw=activatable U=0 visible=true " +
                    "visibleRequested=true mode=freeform translucent=true sz=0}",
            )
            appendLine("    mDeskRootTaskType=activatable")
            val padding = "  * Task{pad #444 type=undefined dw=minimized U=0 visible=false " +
                "visibleRequested=false mode=freeform translucent=true sz=0}" + "x".repeat(200)
            repeat(4_000) { appendLine(padding) }
            appendLine(
                "  * Task{late #999 type=undefined dw=activatable U=0 visible=true " +
                    "visibleRequested=true mode=freeform translucent=true sz=0}",
            )
        }

        assertThrows(TopologyOutputTooLargeException::class.java) {
            TopologyDumpFilter.filter(oversized)
        }
        // Streaming variant fails closed identically: no partial output
        // exists that could ever be fed to the parser.
        assertThrows(TopologyOutputTooLargeException::class.java) {
            TopologyDumpFilter.filterLines(oversized.lineSequence())
        }
    }
}

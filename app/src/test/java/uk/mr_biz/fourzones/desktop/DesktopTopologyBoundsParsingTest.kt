package uk.mr_biz.fourzones.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regressions for the additive task-bounds parsing (Phase 2C3A postcondition).
 * Bounds are diagnostic-only: these tests also confirm they never disturb
 * active-desktop selection.
 */
class DesktopTopologyBoundsParsingTest {

    private fun childTaskId(snapshot: DesktopTopologySnapshot, taskId: Int): DesktopTask =
        snapshot.roots.flatMap { it.childTasks }.single { it.taskId == taskId }

    @Test
    fun `normal mBounds attaches to its task`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(441, 196 - 1479, 976)
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        assertEquals(TaskBounds(441, 196, 1479, 976), childTaskId(snapshot, 77).bounds)
        // Bounds do not disturb active-desktop selection.
        assertEquals(ActiveDesktopAssessment.Found(310), snapshot.activeDesktopByDisplay[0])
    }

    @Test
    fun `negative origin bounds parse`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(-50, -20, 950, 580)
        """.trimIndent().replace("-20, 950", "-20 - 950")

        val snapshot = DesktopTopologyParser.parse(dump)

        assertEquals(TaskBounds(-50, -20, 950, 580), childTaskId(snapshot, 77).bounds)
    }

    @Test
    fun `malformed bounds are absent not invented`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(garbage)
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        assertNull(childTaskId(snapshot, 77).bounds)
    }

    @Test
    fun `missing bounds stay null`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        assertNull(childTaskId(snapshot, 77).bounds)
    }

    @Test
    fun `correct bounds attach to the correct task across multiple desktops`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.one U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(0, 45 - 954, 1128)
              * Task{c #7002 type=undefined dw=activatable U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{d #88 type=standard A=10124:com.example.two U=0 rootTaskId=7002 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
                  mBounds=Rect(966, 45 - 1920, 1128)
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        assertEquals(TaskBounds(0, 45, 954, 1128), childTaskId(snapshot, 77).bounds)
        assertEquals(TaskBounds(966, 45, 1920, 1128), childTaskId(snapshot, 88).bounds)
    }

    @Test
    fun `trailing content after the rectangle is rejected as malformed`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(10, 20 - 30, 40) extra trailing junk
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        assertNull(childTaskId(snapshot, 77).bounds)
    }

    @Test
    fun `a bounds line at root indentation after a child does not attach to that child`() {
        // A shallower mBounds line (root-level indentation) printed after the
        // last child header must NOT be captured as that child's bounds.
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
              mBounds=Rect(1, 2 - 3, 4)
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        // The child's own header is indented 4; the stray mBounds is indented 2
        // (shallower), so it is not a property of the child.
        assertNull(childTaskId(snapshot, 77).bounds)
    }

    @Test
    fun `bounds attach to the sibling they follow not the previous sibling`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.one U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(1, 1 - 2, 2)
                * Task{c #78 type=standard A=10124:com.example.two U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(5, 5 - 9, 9)
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        assertEquals(TaskBounds(1, 1, 2, 2), childTaskId(snapshot, 77).bounds)
        assertEquals(TaskBounds(5, 5, 9, 9), childTaskId(snapshot, 78).bounds)
    }

    @Test
    fun `nested winconfig bounds do not overwrite the task bounds`() {
        // The standalone task mBounds appears first; a later config-style line
        // that is not a bare mBounds= line must not touch it. (In production
        // such lines are filtered out entirely.)
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{a #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{b #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(10, 20 - 30, 40)
                  CurrentConfiguration={1.0 winConfig={ mBounds=Rect(999, 999 - 1999, 1999) }}
        """.trimIndent()

        val snapshot = DesktopTopologyParser.parse(dump)

        assertEquals(TaskBounds(10, 20, 30, 40), childTaskId(snapshot, 77).bounds)
    }
}

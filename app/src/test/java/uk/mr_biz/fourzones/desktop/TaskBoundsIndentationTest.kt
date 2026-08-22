package uk.mr_biz.fourzones.desktop

import uk.mr_biz.fourzones.privileged.TopologyDumpFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrated filter -> parser regressions proving task outer bounds attach
 * ONLY at the exact direct-property indentation (child-header indent + 2), so
 * a nested Activity/window/configuration standalone `mBounds=Rect(...)` — even
 * when byte-identical in form — never attaches to the DesktopTask.
 *
 * Fixtures mirror the real dumpsys grammar: root header at 2 columns, child
 * header at 4, the child's direct properties at 6, and the nested activity
 * record's properties at 8+.
 */
class TaskBoundsIndentationTest {

    private fun childBounds(dump: String, taskId: Int): TaskBounds? {
        // Run the PRODUCTION filter, then the parser — the integrated path.
        val snapshot = DesktopTopologyParser.parse(TopologyDumpFilter.filter(dump))
        return snapshot.roots.flatMap { it.childTasks }.single { it.taskId == taskId }.bounds
    }

    @Test
    fun `direct task bound wins over a nested activity bound`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{h #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{c #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(441, 196 - 1479, 976)
                  isMinimized=false
                  * Hist  #0: ActivityRecord{r u0 com.example.app/.Main t77}
                    mBounds=Rect(1, 2 - 3, 4)
                    packageName=com.example.app
        """.trimIndent()

        // Only the direct task bound (indent 6) attaches; the nested activity
        // bound (indent 8) is dropped by the filter and never reaches parsing.
        val filtered = TopologyDumpFilter.filter(dump)
        assertTrue(filtered.contains("mBounds=Rect(441, 196 - 1479, 976)"))
        assertFalse(filtered.contains("mBounds=Rect(1, 2 - 3, 4)"))
        assertEquals(TaskBounds(441, 196, 1479, 976), childBounds(dump, 77))
    }

    @Test
    fun `no direct bound but a nested standalone bound yields null`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{h #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{c #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  isMinimized=false
                  * Hist  #0: ActivityRecord{r u0 com.example.app/.Main t77}
                    mBounds=Rect(1, 2 - 3, 4)
        """.trimIndent()

        assertNull(childBounds(dump, 77))
    }

    @Test
    fun `nested bound before and after other nested properties never attaches`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{h #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{c #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  * Hist  #0: ActivityRecord{r u0 com.example.app/.Main t77}
                    mBounds=Rect(9, 9 - 9, 9)
                    packageName=com.example.app
                    mBounds=Rect(8, 8 - 8, 8)
        """.trimIndent()

        assertNull(childBounds(dump, 77))
    }

    @Test
    fun `direct bound after the nested activity section still attaches to the task`() {
        // A direct property line can follow the activity block at the task's
        // own indentation; it must attach, while the nested one did not.
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{h #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{c #77 type=standard A=10123:com.example.app U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(10, 20 - 30, 40)
        """.trimIndent()

        assertEquals(TaskBounds(10, 20, 30, 40), childBounds(dump, 77))
    }

    @Test
    fun `sibling transition cannot leak nested ownership`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{h #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{c #77 type=standard A=10123:com.example.one U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  * Hist  #0: ActivityRecord{r u0 com.example.one/.Main t77}
                    mBounds=Rect(1, 1 - 1, 1)
                * Task{d #78 type=standard A=10124:com.example.two U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  mBounds=Rect(5, 5 - 9, 9)
        """.trimIndent()

        // Sibling 77 has only a nested bound => null. Sibling 78's direct bound
        // is its own; the nested bound of 77 never leaks to either.
        assertNull(childBounds(dump, 77))
        assertEquals(TaskBounds(5, 5, 9, 9), childBounds(dump, 78))
    }

    @Test
    fun `root transition cannot leak nested ownership`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{h #310 type=undefined dw=activatable U=0 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{c #77 type=standard A=10123:com.example.one U=0 rootTaskId=310 visible=true visibleRequested=true mode=freeform translucent=true sz=1}
                  * Hist  #0: ActivityRecord{r u0 com.example.one/.Main t77}
                    mBounds=Rect(1, 1 - 1, 1)
              * Task{h2 #7002 type=undefined dw=activatable U=0 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
                mDeskRootTaskType=activatable
                * Task{e #88 type=standard A=10125:com.example.three U=0 rootTaskId=7002 visible=false visibleRequested=false mode=freeform translucent=true sz=1}
                  mBounds=Rect(2, 3 - 4, 5)
        """.trimIndent()

        assertNull(childBounds(dump, 77))
        assertEquals(TaskBounds(2, 3, 4, 5), childBounds(dump, 88))
    }
}

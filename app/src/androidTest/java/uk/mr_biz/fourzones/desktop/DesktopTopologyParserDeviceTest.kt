package uk.mr_biz.fourzones.desktop

import androidx.test.ext.junit.runners.AndroidJUnit4
import uk.mr_biz.fourzones.privileged.TopologyDumpFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device guard for regex construction, using the already-present
 * instrumentation infrastructure.
 *
 * Android's ICU regex implementation rejected the original Task-line
 * expression (bare literal `}`) with a PatternSyntaxException thrown from
 * the parser's static initializer — an app-killing failure that host JVM
 * unit tests could not detect because OpenJDK tolerates that syntax.
 * Running the real filter and parser here forces every Phase 2B pattern
 * through the device regex engine.
 */
@RunWith(AndroidJUnit4::class)
class DesktopTopologyParserDeviceTest {

    @Test
    fun filterAndParserPatternsCompileAndParseOnDevice() {
        val dump = buildString {
            appendLine("Display #0 (activities from top to bottom):")
            appendLine(
                "  * Task{abc123 #5003 type=standard A=10123:com.example U=0 " +
                    "visible=true visibleRequested=true mode=freeform translucent=true sz=0}",
            )
            appendLine("    mDeskRootTaskType=activatable")
            appendLine("ActivityTaskSupervisor state:")
            appendLine("  mFocusedApp=ActivityRecord{f u0 com.example/.Main t77}")
        }

        val snapshot = DesktopTopologyParser.parse(TopologyDumpFilter.filter(dump))

        assertEquals(ActiveDesktopAssessment.Found(5003), snapshot.activeDesktopByDisplay[0])
        assertEquals(77, snapshot.focusedTaskId)
    }
}

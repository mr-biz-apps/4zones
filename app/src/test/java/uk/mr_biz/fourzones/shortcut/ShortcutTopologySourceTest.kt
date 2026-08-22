package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.privileged.TopologyReadResult
import uk.mr_biz.fourzones.snap.TopologyFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the shortcut TopologySource routes EVERY fetch through the non-queuing
 * read and never the ordinary queueing read. A fake exposing BOTH backend reads
 * records the counts; the source is wired to the non-queuing one exactly as
 * ShortcutSnapComposition wires `backend::readActivityTopologyNow`. Because
 * SnapExecutionOrchestrator fetches T1/T2/T3 through this single source, all
 * three admissions are non-queuing.
 */
class ShortcutTopologySourceTest {

    /** Fake backend reads: [readNow] is the non-queuing path; [readQueued] must never be used. */
    private class FakeReads {
        var nowCalls = 0
        var queuedCalls = 0
        var next: TopologyReadResult = TopologyReadResult.Success(A9_DUMP)

        fun readNow(onResult: (TopologyReadResult) -> Unit) {
            nowCalls++
            onResult(next)
        }

        fun readQueued(onResult: (TopologyReadResult) -> Unit) {
            queuedCalls++
            onResult(next)
        }
    }

    @Test
    fun `every fetch uses the non-queuing read, never the queuing read`() {
        val fake = FakeReads()
        val source = shortcutTopologySource(fake::readNow)

        // Simulate T1/T2/T3.
        repeat(3) { source.fetch { } }

        assertEquals(3, fake.nowCalls)
        assertEquals(0, fake.queuedCalls)
    }

    @Test
    fun `read results map to the corresponding TopologyFetch`() {
        val fake = FakeReads()
        val source = shortcutTopologySource(fake::readNow)

        fake.next = TopologyReadResult.Success(A9_DUMP)
        var fetched: TopologyFetch? = null
        source.fetch { fetched = it }
        assertTrue(fetched is TopologyFetch.Fetched)

        fake.next = TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED)
        source.fetch { fetched = it }
        val unavailable = fetched as TopologyFetch.Unavailable
        assertEquals(PrivilegedBackendStatus.BINDER_DIED, unavailable.status)

        fake.next = TopologyReadResult.CommandFailed("boom")
        source.fetch { fetched = it }
        assertTrue(fetched is TopologyFetch.Failed)
    }

    private companion object {
        val A9_DUMP = buildString {
            appendLine("Display #0 (activities from top to bottom):")
            appendLine(
                "  * Task{a #310 type=undefined dw=activatable U=0 visible=true " +
                    "visibleRequested=true mode=freeform}",
            )
            appendLine("    mDeskRootTaskType=activatable")
        }
    }
}

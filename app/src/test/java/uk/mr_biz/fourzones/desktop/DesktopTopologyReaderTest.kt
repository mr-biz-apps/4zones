package uk.mr_biz.fourzones.desktop

import uk.mr_biz.fourzones.privileged.PrivilegedBackend
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.privileged.TopologyReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic lifecycle/ordering tests: the fake backend records each
 * read's callback so tests complete them in any order, proving the
 * generation guard. The fake implements the same narrow interface the app
 * uses — there is no path to arbitrary command execution to fake.
 */
class DesktopTopologyReaderTest {

    private class ControllableBackend : PrivilegedBackend {
        val pending = mutableListOf<(TopologyReadResult) -> Unit>()

        override fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit) = Unit
        override fun stop() = Unit
        override fun requestPermission() = Unit
        override fun readActivityTopology(onResult: (TopologyReadResult) -> Unit) {
            pending += onResult
        }

        fun complete(index: Int, result: TopologyReadResult) = pending[index](result)
    }

    private fun successDump(rootTaskId: Int): TopologyReadResult.Success =
        TopologyReadResult.Success(
            buildString {
                appendLine("Display #0 (activities from top to bottom):")
                appendLine(
                    "  * Task{a #$rootTaskId type=undefined dw=activatable U=0 visible=true " +
                        "visibleRequested=true mode=freeform translucent=true sz=0}",
                )
                appendLine("    mDeskRootTaskType=activatable")
            },
        )

    private fun foundRoot(outcome: DesktopTopologyReader.Outcome?): Int {
        val success = outcome as DesktopTopologyReader.Outcome.Success
        val found = success.snapshot.activeDesktopByDisplay
            .getValue(0) as ActiveDesktopAssessment.Found
        return found.rootTaskId
    }

    private class Harness {
        val backend = ControllableBackend()
        val reader = DesktopTopologyReader(backend)
        val published = mutableListOf<DesktopTopologyReader.Outcome>()

        fun start() = reader.start { published += it }
    }

    @Test
    fun `newer read wins and an older read finishing later is discarded`() {
        val h = Harness()
        h.start()

        h.reader.refresh() // request A -> pending[0]
        h.reader.refresh() // request B -> pending[1]

        h.backend.complete(1, successDump(rootTaskId = 222)) // B completes first
        h.backend.complete(0, successDump(rootTaskId = 111)) // A completes later

        // B published; A discarded — the newer result was never overwritten.
        assertEquals(1, h.published.size)
        assertEquals(222, foundRoot(h.published.single()))
    }

    @Test
    fun `read completing after stop is discarded`() {
        val h = Harness()
        h.start()

        h.reader.refresh()
        h.reader.stop()
        h.backend.complete(0, successDump(rootTaskId = 5))

        assertTrue(h.published.isEmpty())
    }

    @Test
    fun `restart creates a fresh generation domain`() {
        val h = Harness()
        h.start()
        h.reader.refresh() // pre-stop request

        h.reader.stop()
        h.start() // restart

        // The pre-stop read completes into the new session: still discarded.
        h.backend.complete(0, successDump(rootTaskId = 5))
        assertTrue(h.published.isEmpty())

        // A fresh read in the new session publishes normally.
        h.reader.refresh()
        h.backend.complete(1, successDump(rootTaskId = 90))
        assertEquals(1, h.published.size)
        assertEquals(90, foundRoot(h.published.single()))
    }

    @Test
    fun `ready auto-read followed by manual refresh publishes only the newest`() {
        val h = Harness()
        h.start()

        h.reader.refresh() // READY-triggered automatic read
        h.reader.refresh() // user presses Refresh

        // Auto-read completes late, after the manual read already published.
        h.backend.complete(1, successDump(rootTaskId = 777))
        h.backend.complete(0, successDump(rootTaskId = 111))

        assertEquals(1, h.published.size)
        assertEquals(777, foundRoot(h.published.single()))
    }

    @Test
    fun `stale binder failure cannot overwrite a newer successful result`() {
        val h = Harness()
        h.start()

        h.reader.refresh() // request A (will die with the binder)
        h.reader.refresh() // request B

        h.backend.complete(1, successDump(rootTaskId = 424))
        h.backend.complete(
            0,
            TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED),
        )

        // The stale failure was discarded; the published state is B's success.
        assertEquals(1, h.published.size)
        assertEquals(424, foundRoot(h.published.single()))
    }

    @Test
    fun `binder failure on the newest read is still published as diagnostic`() {
        val h = Harness()
        h.start()

        h.reader.refresh()
        h.backend.complete(
            0,
            TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED),
        )

        assertEquals(
            listOf<DesktopTopologyReader.Outcome>(
                DesktopTopologyReader.Outcome.BackendUnavailable(
                    PrivilegedBackendStatus.BINDER_DIED,
                ),
            ),
            h.published,
        )
    }

    @Test
    fun `every unavailable status and command failure is diagnostic not a crash`() {
        val statuses = listOf(
            PrivilegedBackendStatus.NOT_INSTALLED,
            PrivilegedBackendStatus.BINDER_UNAVAILABLE,
            PrivilegedBackendStatus.UNSUPPORTED_SERVER,
            PrivilegedBackendStatus.PERMISSION_REQUIRED,
            PrivilegedBackendStatus.PERMISSION_DENIED,
            PrivilegedBackendStatus.BINDER_DIED,
        )
        statuses.forEach { status ->
            val h = Harness()
            h.start()
            h.reader.refresh()
            h.backend.complete(0, TopologyReadResult.BackendUnavailable(status))
            assertEquals(
                DesktopTopologyReader.Outcome.BackendUnavailable(status),
                h.published.single(),
            )
        }

        val h = Harness()
        h.start()
        h.reader.refresh()
        h.backend.complete(0, TopologyReadResult.CommandFailed("dumpsys timed out"))
        assertEquals(
            DesktopTopologyReader.Outcome.Failed("dumpsys timed out"),
            h.published.single(),
        )
    }

    @Test
    fun `refresh before start is a no-op`() {
        val h = Harness()

        h.reader.refresh()

        assertTrue(h.backend.pending.isEmpty())
    }

    @Test
    fun `successful read parses into a per-display snapshot`() {
        val h = Harness()
        h.start()

        h.reader.refresh()
        h.backend.complete(0, successDump(rootTaskId = 12021))

        assertEquals(12021, foundRoot(h.published.single()))
    }
}

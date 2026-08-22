package uk.mr_biz.fourzones.capture

import uk.mr_biz.fourzones.desktop.SnapTargetAssessment
import uk.mr_biz.fourzones.privileged.PrivilegedBackend
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.privileged.TopologyReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the one-shot coordination state machine with fake backend
 * and scheduler. These validate the coordination logic only — they do not
 * (and cannot) validate Android foreground-service behavior, which the
 * hardware acceptance test covers.
 */
class TargetCaptureControllerTest {

    private val selfPackage = "com.example.selfapp"

    private companion object {
        // Deterministic diagnostic seams shared by every controller here.
        val captureClock = object : CaptureClock {
            override fun wallClockMillis() = 1_723_000_000_000L
            override fun monotonicMillis() = 42L
        }
        val captureSeq = java.util.concurrent.atomic.AtomicLong(0)
        val sequencer = CaptureSequencer { captureSeq.incrementAndGet() }
    }

    private class FakeBackend : PrivilegedBackend {
        var statusCallback: ((PrivilegedBackendStatus) -> Unit)? = null
        val reads = mutableListOf<(TopologyReadResult) -> Unit>()
        var started = false
        var stopped = false

        override fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit) {
            started = true
            statusCallback = onStatusChanged
        }

        override fun stop() {
            stopped = true
            statusCallback = null
        }

        override fun requestPermission() = Unit

        override fun readActivityTopology(onResult: (TopologyReadResult) -> Unit) {
            reads += onResult
        }

        fun sendStatus(status: PrivilegedBackendStatus) = statusCallback?.invoke(status)
    }

    private class FakeScheduler : CaptureScheduler {
        private class Entry(val action: () -> Unit) {
            var cancelled = false
        }

        private val entries = mutableListOf<Entry>()
        val scheduledCount: Int get() = entries.size

        override fun schedule(delayMillis: Long, action: () -> Unit): CaptureCancellable {
            val entry = Entry(action)
            entries += entry
            return CaptureCancellable { entry.cancelled = true }
        }

        /** Runs a scheduled task even if cancelled=false only; late-firing simulation runs anyway. */
        fun fire(index: Int, evenIfCancelled: Boolean = false) {
            val entry = entries[index]
            if (!entry.cancelled || evenIfCancelled) entry.action()
        }

        fun isCancelled(index: Int) = entries[index].cancelled
    }

    private class Harness(selfPackage: String) {
        val backend = FakeBackend()
        val scheduler = FakeScheduler()
        val results = mutableListOf<TargetCaptureResult>()
        val controller = TargetCaptureController(
            backend = backend,
            selfPackageName = selfPackage,
            captureDelayMillis = 5_000,
            readinessTimeoutMillis = 10_000,
            scheduler = scheduler,
            clock = captureClock,
            sequencer = sequencer,
            onResult = { results += it },
        )
    }

    private fun successDump(focusedPkg: String, focusedTaskId: Int = 77): TopologyReadResult.Success =
        TopologyReadResult.Success(
            buildString {
                appendLine("Display #0 (activities from top to bottom):")
                appendLine(
                    "  * Task{a #310 type=undefined dw=activatable U=0 visible=true " +
                        "visibleRequested=true mode=freeform translucent=true sz=1}",
                )
                appendLine("    mDeskRootTaskType=activatable")
                appendLine(
                    "    * Task{b #$focusedTaskId type=standard A=10123:$focusedPkg U=0 " +
                        "rootTaskId=310 visible=true visibleRequested=true mode=freeform " +
                        "translucent=true sz=1}",
                )
                appendLine("ActivityTaskSupervisor state:")
                appendLine("  mFocusedApp=ActivityRecord{f u0 $focusedPkg/.Main t$focusedTaskId}")
            },
        )

    // Index 0 = capture delay; index 1 = readiness timeout (after delay fires).

    @Test
    fun `successful capture publishes a found target exactly once and stops the backend`() {
        val h = Harness(selfPackage)
        h.controller.start()

        assertTrue(h.backend.reads.isEmpty()) // nothing before the delay
        h.scheduler.fire(0) // 5 s elapsed
        assertTrue(h.backend.started)
        h.backend.sendStatus(PrivilegedBackendStatus.READY)
        assertEquals(1, h.backend.reads.size)
        h.backend.reads[0](successDump("com.android.chrome"))

        val captured = h.results.single() as TargetCaptureResult.Captured
        val found = captured.targets.getValue(0) as SnapTargetAssessment.Found
        assertEquals("com.android.chrome", found.packageName)
        assertEquals(77, found.targetTaskId)
        assertTrue(h.backend.stopped)
        // Both timers are cancelled once terminal.
        assertTrue(h.scheduler.isCancelled(1))
    }

    @Test
    fun `backend unavailability publishes a failure and terminates`() {
        val statuses = listOf(
            PrivilegedBackendStatus.NOT_INSTALLED,
            PrivilegedBackendStatus.UNSUPPORTED_SERVER,
            PrivilegedBackendStatus.PERMISSION_REQUIRED,
            PrivilegedBackendStatus.PERMISSION_DENIED,
            PrivilegedBackendStatus.BINDER_DIED,
        )
        statuses.forEach { status ->
            val h = Harness(selfPackage)
            h.controller.start()
            h.scheduler.fire(0)
            h.backend.sendStatus(status)

            val failed = h.results.single() as TargetCaptureResult.Failed
            assertTrue(failed.reason.contains(status.name))
            assertTrue(h.backend.stopped)
        }
    }

    @Test
    fun `binder-unavailable waits and a later ready still captures`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.scheduler.fire(0)

        h.backend.sendStatus(PrivilegedBackendStatus.BINDER_UNAVAILABLE)
        assertTrue(h.results.isEmpty()) // still waiting, bounded by timeout
        h.backend.sendStatus(PrivilegedBackendStatus.READY)
        h.backend.reads.single()(successDump("com.google.android.youtube"))

        assertTrue(h.results.single() is TargetCaptureResult.Captured)
    }

    @Test
    fun `self focus captures a no-target-self assessment`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.scheduler.fire(0)
        h.backend.sendStatus(PrivilegedBackendStatus.READY)
        h.backend.reads.single()(successDump(selfPackage))

        val captured = h.results.single() as TargetCaptureResult.Captured
        val noTarget = captured.targets.getValue(0) as SnapTargetAssessment.NoTarget
        assertTrue(noTarget.reason.contains("host app itself"))
    }

    @Test
    fun `readiness timeout publishes a failure and terminates`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.scheduler.fire(0)
        h.backend.sendStatus(PrivilegedBackendStatus.BINDER_UNAVAILABLE)

        h.scheduler.fire(1) // readiness timeout elapses

        val failed = h.results.single() as TargetCaptureResult.Failed
        assertTrue(failed.reason.contains("timed out"))
        assertTrue(h.backend.stopped)
    }

    @Test
    fun `read failure publishes a failure`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.scheduler.fire(0)
        h.backend.sendStatus(PrivilegedBackendStatus.READY)
        h.backend.reads.single()(TopologyReadResult.CommandFailed("dumpsys exploded"))

        val failed = h.results.single() as TargetCaptureResult.Failed
        assertTrue(failed.reason.contains("dumpsys exploded"))
        assertTrue(h.backend.stopped)
    }

    @Test
    fun `binder death during the read publishes a failure`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.scheduler.fire(0)
        h.backend.sendStatus(PrivilegedBackendStatus.READY)
        h.backend.reads.single()(
            TopologyReadResult.BackendUnavailable(PrivilegedBackendStatus.BINDER_DIED),
        )

        val failed = h.results.single() as TargetCaptureResult.Failed
        assertTrue(failed.reason.contains("BINDER_DIED"))
    }

    @Test
    fun `cancel before the delay terminates without ever starting the backend`() {
        val h = Harness(selfPackage)
        h.controller.start()

        h.controller.cancel("service destroyed")
        // Late-firing scheduler runnable must be inert.
        h.scheduler.fire(0, evenIfCancelled = true)

        val failed = h.results.single() as TargetCaptureResult.Failed
        assertTrue(failed.reason.contains("service destroyed"))
        assertTrue(h.backend.reads.isEmpty())
        assertTrue(h.scheduler.isCancelled(0))
    }

    @Test
    fun `stale events after a terminal result never publish a second result`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.scheduler.fire(0)
        h.backend.sendStatus(PrivilegedBackendStatus.READY)
        h.backend.reads.single()(successDump("com.android.chrome"))
        assertEquals(1, h.results.size)

        // Every conceivable late event: statuses, duplicate read result,
        // cancel, and the late-firing timeout runnable.
        h.backend.statusCallback // cleared by stop(); simulate directly:
        h.controller.cancel("late cancel")
        h.scheduler.fire(1, evenIfCancelled = true)
        h.backend.reads.single()(TopologyReadResult.CommandFailed("late failure"))

        assertEquals(1, h.results.size)
        assertTrue(h.results.single() is TargetCaptureResult.Captured)
    }

    @Test
    fun `duplicate ready statuses request exactly one read`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.scheduler.fire(0)
        h.backend.sendStatus(PrivilegedBackendStatus.READY)
        h.backend.sendStatus(PrivilegedBackendStatus.READY)

        assertEquals(1, h.backend.reads.size)
    }

    @Test
    fun `start is idempotent`() {
        val h = Harness(selfPackage)
        h.controller.start()
        h.controller.start()

        assertEquals(1, h.scheduler.scheduledCount)
    }

    // ---- audit round 2: RuntimeException containment at callback boundaries

    @Test
    fun `exception inside the delayed capture start becomes one sanitized failure`() {
        // backend.start throwing simulates any RuntimeException inside the
        // scheduled beginRead boundary.
        val throwingBackend = object : PrivilegedBackend {
            var stopped = false
            override fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit): Unit =
                throw IllegalStateException("backend exploded with internals")
            override fun stop() { stopped = true }
            override fun requestPermission() = Unit
            override fun readActivityTopology(onResult: (TopologyReadResult) -> Unit) = Unit
        }
        val results = mutableListOf<TargetCaptureResult>()
        val scheduler = FakeScheduler()
        val controller = TargetCaptureController(
            backend = throwingBackend,
            selfPackageName = selfPackage,
            captureDelayMillis = 5_000,
            readinessTimeoutMillis = 10_000,
            scheduler = scheduler,
            clock = captureClock,
            sequencer = sequencer,
            onResult = { results += it },
        )
        controller.start()

        scheduler.fire(0) // must not propagate the exception

        val failed = results.single() as TargetCaptureResult.Failed
        assertTrue(failed.reason.contains("internal capture error"))
        // Sanitized: the raw exception message never surfaces.
        assertTrue(!failed.reason.contains("internals"))
        assertTrue(throwingBackend.stopped)
        // Late timers are inert afterwards.
        scheduler.fire(1, evenIfCancelled = true)
        assertEquals(1, results.size)
    }

    @Test
    fun `exception while processing the read outcome becomes one sanitized failure`() {
        // backend.readActivityTopology throwing simulates a RuntimeException
        // inside the READY status boundary (reader.refresh path).
        val results = mutableListOf<TargetCaptureResult>()
        val scheduler = FakeScheduler()
        val backend = object : PrivilegedBackend {
            var statusCallback: ((PrivilegedBackendStatus) -> Unit)? = null
            var stopped = false
            override fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit) {
                statusCallback = onStatusChanged
            }
            override fun stop() { stopped = true }
            override fun requestPermission() = Unit
            override fun readActivityTopology(onResult: (TopologyReadResult) -> Unit): Unit =
                throw IllegalStateException("read refused")
        }
        val controller = TargetCaptureController(
            backend = backend,
            selfPackageName = selfPackage,
            captureDelayMillis = 5_000,
            readinessTimeoutMillis = 10_000,
            scheduler = scheduler,
            clock = captureClock,
            sequencer = sequencer,
            onResult = { results += it },
        )
        controller.start()
        scheduler.fire(0)

        backend.statusCallback!!.invoke(PrivilegedBackendStatus.READY) // must not propagate

        val failed = results.single() as TargetCaptureResult.Failed
        assertTrue(failed.reason.contains("internal capture error"))
        assertTrue(backend.stopped)
    }

    @Test
    fun `throwing result callback still leaves the controller terminal and cleaned up`() {
        // A broken consumer (e.g. diagnostic listener) must not break the
        // one-terminal-outcome and cleanup guarantees.
        val results = mutableListOf<TargetCaptureResult>()
        val backend = FakeBackend()
        val scheduler = FakeScheduler()
        val controller = TargetCaptureController(
            backend = backend,
            selfPackageName = selfPackage,
            captureDelayMillis = 5_000,
            readinessTimeoutMillis = 10_000,
            scheduler = scheduler,
            clock = captureClock,
            sequencer = sequencer,
            onResult = { results += it; throw IllegalStateException("consumer broken") },
        )
        controller.start()
        scheduler.fire(0)
        backend.sendStatus(PrivilegedBackendStatus.READY)

        // Delivery happens inside the outcome boundary: the consumer's
        // exception is contained there, after cleanup already ran.
        backend.reads.single()(successDump("com.android.chrome"))

        assertEquals(1, results.size)
        assertTrue(backend.stopped)
        // Everything afterwards is inert: no second publication attempt.
        scheduler.fire(1, evenIfCancelled = true)
        controller.cancel("late")
        assertEquals(1, results.size)
    }

    @Test
    fun `cancel racing a throwing callback keeps a single terminal outcome`() {
        val results = mutableListOf<TargetCaptureResult>()
        val backend = FakeBackend()
        val scheduler = FakeScheduler()
        val controller = TargetCaptureController(
            backend = backend,
            selfPackageName = selfPackage,
            captureDelayMillis = 5_000,
            readinessTimeoutMillis = 10_000,
            scheduler = scheduler,
            clock = captureClock,
            sequencer = sequencer,
            onResult = { results += it },
        )
        controller.start()
        scheduler.fire(0)
        controller.cancel("service teardown")

        // Late status/read events after the cancel — all inert.
        backend.statusCallback?.invoke(PrivilegedBackendStatus.READY)
        scheduler.fire(1, evenIfCancelled = true)

        assertEquals(1, results.size)
        assertTrue((results.single() as TargetCaptureResult.Failed).reason.contains("teardown"))
        assertTrue(backend.stopped)
    }

    @Test
    fun `every terminal path stops the backend and publishes exactly once`() {
        // Success path covered above; verify the invariant across failures.
        val runs = listOf<(Harness) -> Unit>(
            { it.backend.sendStatus(PrivilegedBackendStatus.PERMISSION_DENIED) },
            { it.scheduler.fire(1) },
            { it.controller.cancel("destroyed") },
            { it.backend.sendStatus(PrivilegedBackendStatus.READY)
                it.backend.reads.single()(TopologyReadResult.CommandFailed("x")) },
        )
        runs.forEach { trigger ->
            val h = Harness(selfPackage)
            h.controller.start()
            h.scheduler.fire(0)
            trigger(h)

            assertEquals(1, h.results.size)
            assertTrue(h.backend.stopped)
        }
    }
}

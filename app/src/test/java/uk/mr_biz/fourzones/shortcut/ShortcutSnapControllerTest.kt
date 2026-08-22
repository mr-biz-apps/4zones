package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.geometry.GeometryRect
import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.snap.SnapExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controller coverage with a fake executor — the controller's ONLY downstream is
 * the [ShortcutSnapExecutor] abstraction (it holds no TaskResizeGateway, parser,
 * resolver, or geometry reference), so these tests fully characterize its
 * behavior without any mutation. No real orchestrator or `cmd activity task
 * resize` is involved.
 */
class ShortcutSnapControllerTest {

    /** Records requests and lets the test complete them on demand. */
    private class FakeExecutor : ShortcutSnapExecutor {
        val calls = mutableListOf<Quadrant>()
        private val pending = ArrayDeque<(SnapExecutionResult) -> Unit>()
        var lastIsCancelled: (() -> Boolean)? = null

        override fun execute(
            quadrant: Quadrant,
            isCancelled: () -> Boolean,
            onResult: (SnapExecutionResult) -> Unit,
        ) {
            calls += quadrant
            lastIsCancelled = isCancelled
            pending.addLast(onResult)
        }

        val outstanding get() = pending.size
        fun completeFirst(result: SnapExecutionResult) = pending.removeFirst()(result)
    }

    private val events = mutableListOf<ShortcutSnapEvent>()
    private var readiness = PrivilegedBackendStatus.READY

    private fun controller(executor: FakeExecutor): ShortcutSnapController {
        val c = ShortcutSnapController({ readiness }, executor) { events += it }
        c.start()
        return c
    }

    private fun applied(q: Quadrant) = SnapExecutionResult.AppliedAndVerified(
        quadrant = q, displayId = 0, taskId = 1, packageName = "com.x", componentName = "com.x/.M",
        bounds = GeometryRect(0, 0, 10, 10),
    )

    @Test
    fun `successful execution reports one Requested then one Completed`() {
        val ex = FakeExecutor()
        val c = controller(ex)

        c.requestSnap(Quadrant.TOP_LEFT)
        assertEquals(listOf(Quadrant.TOP_LEFT), ex.calls)
        assertEquals(listOf<ShortcutSnapEvent>(ShortcutSnapEvent.Requested(1, Quadrant.TOP_LEFT)), events)

        ex.completeFirst(applied(Quadrant.TOP_LEFT))
        assertEquals(2, events.size)
        val completed = events[1] as ShortcutSnapEvent.Completed
        assertEquals(1, completed.requestId)
        assertTrue(completed.result is SnapExecutionResult.AppliedAndVerified)
    }

    @Test
    fun `failed execution result is reported once`() {
        val ex = FakeExecutor()
        val c = controller(ex)

        c.requestSnap(Quadrant.TOP_RIGHT)
        ex.completeFirst(SnapExecutionResult.NoTarget(Quadrant.TOP_RIGHT, "launcher"))

        val completed = events.filterIsInstance<ShortcutSnapEvent.Completed>().single()
        assertTrue(completed.result is SnapExecutionResult.NoTarget)
    }

    @Test
    fun `a rapid second request is rejected busy and never executed or replayed`() {
        val ex = FakeExecutor()
        val c = controller(ex)

        c.requestSnap(Quadrant.TOP_LEFT) // in flight
        c.requestSnap(Quadrant.TOP_RIGHT) // busy: dropped

        // Only the first ever reached the executor.
        assertEquals(listOf(Quadrant.TOP_LEFT), ex.calls)
        assertEquals(1, ex.outstanding)
        val busy = events.filterIsInstance<ShortcutSnapEvent.RejectedBusy>().single()
        assertEquals(Quadrant.TOP_RIGHT, busy.quadrant)

        // Completing the first does NOT replay the rejected second.
        ex.completeFirst(applied(Quadrant.TOP_LEFT))
        assertEquals(listOf(Quadrant.TOP_LEFT), ex.calls)
        assertTrue(events.none { it is ShortcutSnapEvent.Completed && it.quadrant == Quadrant.TOP_RIGHT })
    }

    @Test
    fun `after completion a new request executes normally`() {
        val ex = FakeExecutor()
        val c = controller(ex)

        c.requestSnap(Quadrant.TOP_LEFT)
        ex.completeFirst(applied(Quadrant.TOP_LEFT))
        c.requestSnap(Quadrant.BOTTOM_RIGHT)

        assertEquals(listOf(Quadrant.TOP_LEFT, Quadrant.BOTTOM_RIGHT), ex.calls)
    }

    @Test
    fun `backend-not-ready result is surfaced once and never queued`() {
        val ex = FakeExecutor()
        val c = controller(ex)

        c.requestSnap(Quadrant.TOP_LEFT)
        ex.completeFirst(
            SnapExecutionResult.PrivilegeUnavailable(
                Quadrant.TOP_LEFT, PrivilegedBackendStatus.CONNECTING,
            ),
        )

        val completed = events.filterIsInstance<ShortcutSnapEvent.Completed>().single()
        assertTrue(completed.result is SnapExecutionResult.PrivilegeUnavailable)
        // Nothing re-executed automatically; the slot is free for a fresh press.
        assertEquals(1, ex.calls.size)
    }

    @Test
    fun `stop invalidates an in-flight result and its cancel predicate trips`() {
        val ex = FakeExecutor()
        val c = controller(ex)

        c.requestSnap(Quadrant.TOP_LEFT)
        val cancelled = ex.lastIsCancelled!!
        assertFalse(cancelled()) // active, current

        c.stop()
        assertTrue(cancelled()) // stop trips the pre-mutation cancel

        // A late orchestrator result after stop is inert (no Completed event).
        ex.completeFirst(applied(Quadrant.TOP_LEFT))
        assertTrue(events.none { it is ShortcutSnapEvent.Completed })
    }

    @Test
    fun `inactive controller ignores requests`() {
        val ex = FakeExecutor()
        val c = ShortcutSnapController({ readiness }, ex) { events += it } // not started

        c.requestSnap(Quadrant.TOP_LEFT)

        assertTrue(ex.calls.isEmpty())
        assertTrue(events.isEmpty())
    }

    // ---- readiness gate: non-ready fails terminally, never queued/replayed

    @Test
    fun `a request while not ready fails terminally without calling the executor`() {
        val ex = FakeExecutor()
        val c = controller(ex)
        readiness = PrivilegedBackendStatus.CONNECTING

        c.requestSnap(Quadrant.TOP_LEFT)

        assertTrue(ex.calls.isEmpty()) // orchestrator never reached
        val completed = events.filterIsInstance<ShortcutSnapEvent.Completed>().single()
        val result = completed.result as SnapExecutionResult.PrivilegeUnavailable
        assertEquals(PrivilegedBackendStatus.CONNECTING, result.status)
    }

    @Test
    fun `a not-ready request is never executed after the backend later becomes ready`() {
        val ex = FakeExecutor()
        val c = controller(ex)

        readiness = PrivilegedBackendStatus.CONNECTING
        c.requestSnap(Quadrant.TOP_LEFT) // terminal PrivilegeUnavailable now
        assertTrue(ex.calls.isEmpty())

        // Backend becomes ready; the OLD request must not be retried/replayed.
        readiness = PrivilegedBackendStatus.READY
        assertTrue(ex.calls.isEmpty())
        assertEquals(1, events.filterIsInstance<ShortcutSnapEvent.Completed>().size)

        // A NEW request while ready executes exactly once.
        c.requestSnap(Quadrant.BOTTOM_RIGHT)
        assertEquals(listOf(Quadrant.BOTTOM_RIGHT), ex.calls)
    }

    // ---- synchronous submission failure containment

    @Test
    fun `a synchronous executor throw yields one SubmissionFailed and does not escape`() {
        val throwing = object : ShortcutSnapExecutor {
            override fun execute(
                quadrant: Quadrant,
                isCancelled: () -> Boolean,
                onResult: (SnapExecutionResult) -> Unit,
            ): Unit = throw IllegalStateException("boom")
        }
        val c = ShortcutSnapController({ readiness }, throwing) { events += it }
        c.start()

        c.requestSnap(Quadrant.TOP_LEFT) // must not escape to the caller

        val failed = events.filterIsInstance<ShortcutSnapEvent.SubmissionFailed>().single()
        assertEquals(Quadrant.TOP_LEFT, failed.quadrant)
        // No mislabeling as a snap outcome, and the slot is not stuck busy: a
        // second request on the SAME controller is accepted (not RejectedBusy).
        assertTrue(events.none { it is ShortcutSnapEvent.Completed })
        c.requestSnap(Quadrant.TOP_RIGHT)
        assertTrue(events.none { it is ShortcutSnapEvent.RejectedBusy })
        assertEquals(2, events.filterIsInstance<ShortcutSnapEvent.SubmissionFailed>().size)
    }

    @Test
    fun `later request after a throw executes on the same controller`() {
        var throwNext = true
        val ex = FakeExecutor()
        val executor = object : ShortcutSnapExecutor {
            override fun execute(
                quadrant: Quadrant,
                isCancelled: () -> Boolean,
                onResult: (SnapExecutionResult) -> Unit,
            ) {
                if (throwNext) {
                    throwNext = false
                    throw IllegalStateException("boom")
                }
                ex.execute(quadrant, isCancelled, onResult)
            }
        }
        val c = ShortcutSnapController({ readiness }, executor) { events += it }
        c.start()

        c.requestSnap(Quadrant.TOP_LEFT) // throws -> SubmissionFailed, slot freed
        c.requestSnap(Quadrant.BOTTOM_LEFT) // now executes

        assertEquals(1, events.filterIsInstance<ShortcutSnapEvent.SubmissionFailed>().size)
        assertEquals(listOf(Quadrant.BOTTOM_LEFT), ex.calls)
    }

    @Test
    fun `callback then synchronous throw yields exactly one terminal and frees the slot`() {
        val executor = object : ShortcutSnapExecutor {
            override fun execute(
                quadrant: Quadrant,
                isCancelled: () -> Boolean,
                onResult: (SnapExecutionResult) -> Unit,
            ) {
                onResult(applied(quadrant)) // deliver a terminal...
                throw IllegalStateException("boom after callback") // ...then throw
            }
        }
        val c = ShortcutSnapController({ readiness }, executor) { events += it }
        c.start()

        c.requestSnap(Quadrant.TOP_LEFT)

        // Exactly one terminal: the Completed from the callback, no SubmissionFailed.
        assertEquals(1, events.filterIsInstance<ShortcutSnapEvent.Completed>().size)
        assertTrue(events.none { it is ShortcutSnapEvent.SubmissionFailed })

        // Slot freed on the SAME controller: a second request is not RejectedBusy.
        c.requestSnap(Quadrant.TOP_RIGHT)
        assertTrue(events.none { it is ShortcutSnapEvent.RejectedBusy })
        assertEquals(2, events.filterIsInstance<ShortcutSnapEvent.Completed>().size)
    }

    @Test
    fun `a duplicate executor callback is inert and the next request is accepted`() {
        val executor = object : ShortcutSnapExecutor {
            override fun execute(
                quadrant: Quadrant,
                isCancelled: () -> Boolean,
                onResult: (SnapExecutionResult) -> Unit,
            ) {
                onResult(applied(quadrant)) // first terminal wins
                onResult(applied(quadrant)) // duplicate: inert
            }
        }
        val c = ShortcutSnapController({ readiness }, executor) { events += it }
        c.start()

        c.requestSnap(Quadrant.TOP_LEFT)
        assertEquals(1, events.filterIsInstance<ShortcutSnapEvent.Completed>().size)

        // Not stuck busy: a second request is accepted and completes.
        c.requestSnap(Quadrant.BOTTOM_RIGHT)
        assertTrue(events.none { it is ShortcutSnapEvent.RejectedBusy })
        assertEquals(2, events.filterIsInstance<ShortcutSnapEvent.Completed>().size)
    }
}

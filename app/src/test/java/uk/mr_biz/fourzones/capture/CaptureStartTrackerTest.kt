package uk.mr_biz.fourzones.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure start/controller lifetime state machine the
 * service delegates to. These validate the coordination semantics only —
 * not Android Service runtime behavior.
 */
class CaptureStartTrackerTest {

    private val captureA = Any()
    private val captureB = Any()

    // E. Start B during running A: no second concurrent capture.
    @Test
    fun `start delivered while a capture is active is coalesced`() {
        val tracker = CaptureStartTracker()

        assertTrue(tracker.onStartDelivered(1))
        tracker.activate(captureA, 1)

        assertFalse(tracker.onStartDelivered(2)) // coalesced, no second capture
        assertTrue(tracker.isActive(captureA))
        assertEquals(2, tracker.latestStartId) // teardown will stop the newest start too
    }

    // D. Completion clears active state synchronously.
    @Test
    fun `complete clears the active capture synchronously`() {
        val tracker = CaptureStartTracker()
        tracker.onStartDelivered(1)
        tracker.activate(captureA, 1)

        tracker.complete()

        assertFalse(tracker.hasActive())
        assertFalse(tracker.isActive(captureA))
        assertEquals(CaptureStartTracker.NO_START_ID, tracker.activeStartId)
    }

    // F. Start after completion but before destruction is NOT lost.
    @Test
    fun `start delivered after completion begins a fresh capture`() {
        val tracker = CaptureStartTracker()
        tracker.onStartDelivered(1)
        tracker.activate(captureA, 1)
        tracker.complete()

        assertTrue(tracker.onStartDelivered(2)) // eligible: no stale reference blocks it
        tracker.activate(captureB, 2)
        assertTrue(tracker.isActive(captureB))
    }

    // G. Old completion cannot stop a newer accepted start.
    @Test
    fun `old capture completion uses its own latest id and stale results are inert`() {
        val tracker = CaptureStartTracker()
        tracker.onStartDelivered(1)
        tracker.activate(captureA, 1)

        // A completes while it is still the only delivered start: stopSelf
        // id is 1. A start accepted afterwards has a newer id (2), which
        // Android does not stop for stopSelf(1).
        assertEquals(1, tracker.complete())
        assertTrue(tracker.onStartDelivered(2))
        tracker.activate(captureB, 2)

        // Any stale callback from A is inert by identity.
        assertFalse(tracker.isActive(captureA))
        assertTrue(tracker.isActive(captureB))
        assertEquals(2, tracker.complete())
    }

    // Coalescing variant of G: B delivered DURING A; both stop together.
    @Test
    fun `coalesced start stops together with the capture that served it`() {
        val tracker = CaptureStartTracker()
        tracker.onStartDelivered(1)
        tracker.activate(captureA, 1)
        assertFalse(tracker.onStartDelivered(2)) // coalesced into A

        // Defined behavior: A's completion stops the newest delivered start
        // as well, because start 2's request was served by A's capture.
        assertEquals(2, tracker.complete())
        assertFalse(tracker.hasActive())
    }

    // H. Repeated completion is idempotent.
    @Test
    fun `repeated completion stays idempotent`() {
        val tracker = CaptureStartTracker()
        tracker.onStartDelivered(1)
        tracker.activate(captureA, 1)

        assertEquals(1, tracker.complete())
        assertEquals(1, tracker.complete()) // still the latest id, still inactive
        assertFalse(tracker.hasActive())
    }

    @Test
    fun `start ids are opaque and non-sequential values work identically`() {
        val tracker = CaptureStartTracker()
        assertTrue(tracker.onStartDelivered(2147000000))
        tracker.activate(captureA, 2147000000)
        assertFalse(tracker.onStartDelivered(7))
        assertEquals(7, tracker.complete())
    }
}

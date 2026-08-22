package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.geometry.Quadrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the AccessibilityService's connection lifecycle retires the prior
 * session on reconnect and never lets a retired session publish into the current
 * one — without instantiating the framework service.
 */
class ShortcutServiceLifecycleTest {

    private class FakeSession(val sink: (ShortcutSnapEvent) -> Unit) : ShortcutSession {
        var started = false
        var stopCount = 0
        var handleKeyCalls = 0
        var resetInputCount = 0
        override fun start() { started = true }
        override fun stop() { stopCount++ }
        override fun resetInputOwnership() { resetInputCount++ }
        override fun handleKey(
            deviceId: Int,
            action: Int,
            keyCode: Int,
            repeatCount: Int,
            altPressed: Boolean,
            metaPressed: Boolean,
            ctrlPressed: Boolean,
            shiftPressed: Boolean,
        ): ShortcutKeyDecision {
            handleKeyCalls++
            return ShortcutKeyDecision(null, consume = false)
        }

        /** Simulate a late async event from this (possibly retired) session. */
        fun emit(quadrant: Quadrant) = sink(ShortcutSnapEvent.Requested(1, quadrant))
    }

    private val published = mutableListOf<ShortcutSnapEvent>()
    private val created = mutableListOf<FakeSession>()

    private fun lifecycle() = ShortcutServiceLifecycle(
        onEvent = { published += it },
        sessionFactory = { sink -> FakeSession(sink).also { created += it } },
    )

    @Test
    fun `reconnect retires the previous session and keeps exactly one active`() {
        val lc = lifecycle()

        lc.onConnected()
        val a = created.single()
        assertTrue(a.started)

        lc.onConnected()
        assertEquals(2, created.size)
        val b = created[1]
        assertEquals(1, a.stopCount) // A retired exactly once
        assertTrue(b.started)
        assertEquals(0, b.stopCount) // B still active
    }

    @Test
    fun `a late event from a retired session is not published into the new lifecycle`() {
        val lc = lifecycle()

        lc.onConnected()
        val a = created[0]
        lc.onConnected()
        val b = created[1]

        a.emit(Quadrant.TOP_LEFT) // retired A fires late
        assertTrue(published.isEmpty()) // ignored

        b.emit(Quadrant.TOP_RIGHT) // current B fires
        assertEquals(1, published.size)
        assertEquals(Quadrant.TOP_RIGHT, published.single().quadrant)
    }

    @Test
    fun `repeated reconnects do not accumulate active sessions`() {
        val lc = lifecycle()

        repeat(4) { lc.onConnected() }

        // Every session but the last was retired exactly once; the last is active.
        val last = created.last()
        created.dropLast(1).forEach { assertEquals(1, it.stopCount) }
        assertEquals(0, last.stopCount)
        assertEquals(4, created.size)
    }

    @Test
    fun `onDestroyed stops the current session once and later events are inert`() {
        val lc = lifecycle()
        lc.onConnected()
        val a = created.single()

        lc.onDestroyed()
        assertEquals(1, a.stopCount)

        a.emit(Quadrant.TOP_LEFT) // late event after destroy
        assertTrue(published.isEmpty())

        lc.onDestroyed() // idempotent: no additional stop on a null current
        assertEquals(1, a.stopCount)
    }

    @Test
    fun `resetInputOwnership routes to the current session and is harmless with none`() {
        val lc = lifecycle()

        // No session yet: harmless.
        lc.resetInputOwnership()

        lc.onConnected()
        val a = created.single()
        lc.resetInputOwnership()
        lc.resetInputOwnership()
        assertEquals(2, a.resetInputCount) // routed to current session; idempotent
        assertEquals(0, a.stopCount) // did NOT stop/retire the session
    }

    @Test
    fun `key events route only to the current session`() {
        val lc = lifecycle()
        lc.onConnected()
        val a = created[0]
        lc.onConnected()
        val b = created[1]
        assertFalse(a === b)

        lc.handleKey(
            0, 0, 0, 0,
            altPressed = true, metaPressed = true, ctrlPressed = false, shiftPressed = false,
        )

        assertEquals(0, a.handleKeyCalls) // retired A never routed to
        assertEquals(1, b.handleKeyCalls) // current B receives it
        assertSame(b, created.last())
    }
}

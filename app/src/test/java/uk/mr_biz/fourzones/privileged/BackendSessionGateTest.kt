package uk.mr_biz.fourzones.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the lifecycle-session attribution used to stamp the global Shizuku
 * listeners: a callback captured in one session must be inert once a later
 * session is active, while the current session's callback acts normally. The
 * listeners are simulated as token-guarded lambdas exactly as the wrapper wires
 * them — no Shizuku involved.
 */
class BackendSessionGateTest {

    @Test
    fun `tokens advance monotonically and a closed session is no longer active`() {
        val gate = BackendSessionGate()

        val a = gate.open()
        assertTrue(gate.isActive(a))

        gate.close()
        assertFalse(gate.isActive(a))

        val b = gate.open()
        assertTrue(b > a) // never reset
        assertTrue(gate.isActive(b))
        assertFalse(gate.isActive(a))
    }

    @Test
    fun `a delayed lifecycle-A death callback is ignored while lifecycle B is active`() {
        val gate = BackendSessionGate()
        var deaths = 0

        // Lifecycle A registers a token-stamped listener, then stops.
        val sessionA = gate.open()
        val listenerA = { if (gate.isActive(sessionA)) deaths++ }
        gate.close()

        // Lifecycle B starts and registers its own listener.
        val sessionB = gate.open()
        val listenerB = { if (gate.isActive(sessionB)) deaths++ }

        // A's delayed binder-death callback fires now.
        listenerA()
        assertEquals(0, deaths) // ignored: B is healthy and unaffected

        // B's own binder-death callback fires: normal handling.
        listenerB()
        assertEquals(1, deaths)
    }

    @Test
    fun `only the current session's listener acts across repeated start-stop`() {
        val gate = BackendSessionGate()

        val s1 = gate.open()
        gate.close()
        val s2 = gate.open()
        gate.close()
        val s3 = gate.open()

        assertFalse(gate.isActive(s1))
        assertFalse(gate.isActive(s2))
        assertTrue(gate.isActive(s3))
    }
}

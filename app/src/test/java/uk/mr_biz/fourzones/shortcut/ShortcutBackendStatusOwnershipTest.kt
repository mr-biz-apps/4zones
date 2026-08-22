package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves 4B1 status-publication OWNERSHIP through the real
 * [ShortcutServiceLifecycle] and the real [ShortcutBackendStatusStore]:
 *
 * - the session teardown order is controller.stop → backend.stop →
 *   store.clear (backend.stop strictly before clear);
 * - a retired session's status callback cannot republish, because the backend
 *   itself revokes callback authority on stop (started=false + callback
 *   nulled) — the same contract `ShizukuPrivilegedBackend.stop()` implements
 *   in production;
 * - reconnect ordering leaves the store owned by the NEW session.
 *
 * `ShizukuPrivilegedBackend`/`ShortcutSnapComposition` construct framework +
 * Shizuku objects and cannot be instantiated on the JVM, so the backend here
 * is a fake that enforces the production retirement contract at the same
 * seam: status flows ONLY through the callback handed to `start`, and `stop`
 * makes that callback authority inert BEFORE the session clears the store.
 * The session mirrors [ShortcutSnapComposition]'s exact start/stop order.
 */
class ShortcutBackendStatusOwnershipTest {

    /** Ordered journal of lifecycle actions, for explicit order assertions. */
    private val journal = mutableListOf<String>()

    /**
     * Enforces the production backend retirement contract: the callback is
     * held only while started, and publication is refused once stopped —
     * matching ShizukuPrivilegedBackend (started=false, onStatusChanged=null,
     * session gate closed).
     */
    private inner class FakeStatusBackend(private val name: String) {
        private var started = false
        private var onStatusChanged: ((PrivilegedBackendStatus) -> Unit)? = null

        fun start(onStatusChanged: (PrivilegedBackendStatus) -> Unit) {
            started = true
            this.onStatusChanged = onStatusChanged
        }

        fun stop() {
            journal += "$name.backend.stop"
            started = false
            onStatusChanged = null
        }

        /**
         * Drives a status change through the backend — the ONLY path to the
         * callback, as in production. Inert once retired.
         */
        fun driveStatus(status: PrivilegedBackendStatus) {
            if (!started) return // retired: publication refused
            onStatusChanged?.invoke(status)
        }
    }

    /** Mirrors ShortcutSnapComposition's start wiring and exact stop order. */
    private inner class FakeCompositionSession(
        val name: String,
        val backend: FakeStatusBackend,
    ) : ShortcutSession {
        override fun start() {
            journal += "$name.start"
            backend.start { status -> ShortcutBackendStatusStore.publish(status) }
        }

        override fun stop() {
            journal += "$name.controller.stop"
            backend.stop()
            journal += "$name.store.clear"
            ShortcutBackendStatusStore.clear()
        }

        override fun resetInputOwnership() = Unit
        override fun handleKey(
            deviceId: Int,
            action: Int,
            keyCode: Int,
            repeatCount: Int,
            altPressed: Boolean,
            metaPressed: Boolean,
            ctrlPressed: Boolean,
            shiftPressed: Boolean,
        ): ShortcutKeyDecision = ShortcutKeyDecision(null, consume = false)
    }

    private val sessions = mutableListOf<FakeCompositionSession>()

    private fun lifecycle() = ShortcutServiceLifecycle(
        onEvent = { },
        sessionFactory = { _ ->
            FakeCompositionSession("s${sessions.size}", FakeStatusBackend("s${sessions.size}"))
                .also { sessions += it }
        },
    )

    @Before
    fun setUp() = resetStore()

    @After
    fun tearDown() = resetStore()

    private fun resetStore() {
        ShortcutBackendStatusStore.setListener(null)
        ShortcutBackendStatusStore.clear()
    }

    @Test
    fun `session teardown stops the backend strictly before clearing the store`() {
        val lc = lifecycle()
        lc.onConnected()
        sessions.single().backend.driveStatus(PrivilegedBackendStatus.READY)
        assertEquals(PrivilegedBackendStatus.READY, ShortcutBackendStatusStore.latest())

        lc.onDestroyed()

        val stopIndex = journal.indexOf("s0.backend.stop")
        val clearIndex = journal.indexOf("s0.store.clear")
        assertTrue("backend.stop must be journaled", stopIndex >= 0)
        assertTrue("store.clear must be journaled", clearIndex >= 0)
        assertTrue("backend.stop must precede store.clear", stopIndex < clearIndex)
        assertTrue(
            "controller.stop must precede backend.stop",
            journal.indexOf("s0.controller.stop") < stopIndex,
        )
        assertNull(ShortcutBackendStatusStore.latest())
    }

    @Test
    fun `a retired session cannot republish READY after the clear`() {
        val lc = lifecycle()
        lc.onConnected()
        val retired = sessions.single()
        retired.backend.driveStatus(PrivilegedBackendStatus.READY)

        lc.onDestroyed()
        assertNull(ShortcutBackendStatusStore.latest())

        // Stimulate the retired backend through its only status seam: the
        // revoked callback authority (started=false, callback nulled) makes
        // this inert, exactly as ShizukuPrivilegedBackend.stop() guarantees.
        retired.backend.driveStatus(PrivilegedBackendStatus.READY)

        assertNull(ShortcutBackendStatusStore.latest())
    }

    @Test
    fun `reconnect clears the old session's status before the new session publishes`() {
        val observed = mutableListOf<PrivilegedBackendStatus?>()
        ShortcutBackendStatusStore.setListener { observed += it }

        val lc = lifecycle()
        lc.onConnected()
        val old = sessions[0]
        old.backend.driveStatus(PrivilegedBackendStatus.READY)

        lc.onConnected() // reconnect: retires old, then starts new
        val fresh = sessions[1]
        fresh.backend.driveStatus(PrivilegedBackendStatus.CONNECTING)
        fresh.backend.driveStatus(PrivilegedBackendStatus.READY)

        // Old publishes, then its retirement clears, then ONLY the new session
        // publishes fresh status.
        assertEquals(
            listOf(
                PrivilegedBackendStatus.READY, // old session, while live
                null, // old session retired: final observation is the clear
                PrivilegedBackendStatus.CONNECTING, // new session
                PrivilegedBackendStatus.READY, // new session
            ),
            observed,
        )
        // Ordering journal: old teardown completes before the new start.
        assertTrue(journal.indexOf("s0.store.clear") < journal.indexOf("s1.start"))

        // The retired session can no longer disturb the new owner's value.
        old.backend.driveStatus(PrivilegedBackendStatus.BINDER_DIED)
        assertEquals(PrivilegedBackendStatus.READY, ShortcutBackendStatusStore.latest())
    }

    @Test
    fun `destroy after reconnects leaves null as the final observation`() {
        val lc = lifecycle()
        repeat(3) { lc.onConnected() }
        sessions.last().backend.driveStatus(PrivilegedBackendStatus.READY)

        lc.onDestroyed()

        assertNull(ShortcutBackendStatusStore.latest())
        // Every retired/destroyed session stopped its backend before its clear.
        sessions.indices.forEach { i ->
            val stop = journal.indexOf("s$i.backend.stop")
            val clear = journal.indexOf("s$i.store.clear")
            assertTrue(stop in 0 until clear)
        }
    }
}

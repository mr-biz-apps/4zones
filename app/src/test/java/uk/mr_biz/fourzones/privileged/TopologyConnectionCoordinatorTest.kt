package uk.mr_biz.fourzones.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic ordered-callback coverage for the generation-aware connection
 * coordinator — including restart lifecycle and generation-owned read
 * completion. No Shizuku, no threads: the test drives every event in an exact
 * sequence (dispatched reads are completed explicitly via [onReadCompleted]) and
 * asserts obsolete callbacks are inert and every read completes exactly once.
 */
class TopologyConnectionCoordinatorTest {

    private val expected = 2

    /** Identity-comparable fake service; [alive] models binder liveness. */
    private class FakeService(var alive: Boolean = true)

    private data class Dispatch(val readId: Long, val generation: Long, val service: FakeService)

    private class Recorder : TopologyConnectionCoordinator.Effects<FakeService> {
        val binds = mutableListOf<Long>()
        val removes = mutableListOf<Long>()
        val handshakes = mutableListOf<Pair<Long, FakeService>>()
        val dispatches = mutableListOf<Dispatch>()
        var statusChanges = 0

        override fun bind(generation: Long) { binds += generation }
        override fun removeStale(generation: Long) { removes += generation }
        override fun startHandshake(generation: Long, candidate: FakeService) {
            handshakes += generation to candidate
        }
        override fun executeRead(readId: Long, generation: Long, service: FakeService) {
            dispatches += Dispatch(readId, generation, service)
        }
        override fun onStatusChanged() { statusChanges++ }
    }

    private class ReadProbe {
        val outcomes = mutableListOf<TopologyReadResult>()
        val onResult: (TopologyReadResult) -> Unit = { outcomes += it }
        val completions get() = outcomes.size
        fun single() = outcomes.single()
    }

    private fun started(rec: Recorder): TopologyConnectionCoordinator<FakeService> {
        val c = TopologyConnectionCoordinator(expected, { s: FakeService -> s.alive }, rec)
        c.start()
        return c
    }

    /** Completes a recorded dispatch as its worker would, through the coordinator. */
    private fun deliver(
        c: TopologyConnectionCoordinator<FakeService>,
        rec: Recorder,
        index: Int,
        result: TopologyReadResult = TopologyReadResult.Success("dump"),
    ) {
        val d = rec.dispatches[index]
        c.onReadCompleted(d.readId, d.generation, d.service, result)
    }

    /** Drives a fresh acquisition to a verified service, returning it. */
    private fun verify(
        c: TopologyConnectionCoordinator<FakeService>,
        rec: Recorder,
        version: Int = 2,
    ): FakeService {
        val g = rec.binds.last()
        val svc = FakeService()
        c.onConnected(g, svc)
        c.onHandshakeResult(g, svc, version)
        return svc
    }

    private fun mismatchStatus(r: TopologyReadResult) =
        (r as TopologyReadResult.BackendUnavailable).status

    // -------------------------------------------------- baseline + handshake

    @Test
    fun `matching service verifies and a dispatched read is delivered once`() {
        val rec = Recorder()
        val c = started(rec)
        val p = ReadProbe()

        c.requestRead(p.onResult)
        val a = verify(c, rec)

        assertTrue(c.isVerified)
        assertSame(a, c.verifiedServiceOrNull())
        assertEquals(1, rec.dispatches.size)
        assertEquals(0, p.completions) // not delivered until the worker completes
        deliver(c, rec, 0)
        assertEquals(1, p.completions)
        assertTrue(p.single() is TopologyReadResult.Success)
    }

    @Test
    fun `delayed handshake from a superseded connection is ignored`() {
        val rec = Recorder()
        val c = started(rec)

        val p1 = ReadProbe()
        c.requestRead(p1.onResult)
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onDisconnected(g1)
        assertEquals(1, p1.completions)

        val p2 = ReadProbe()
        c.requestRead(p2.onResult)
        val g2 = rec.binds.last()
        assertNotEquals(g1, g2)
        val b = verify(c, rec)

        c.onHandshakeResult(g1, a, 2) // delayed, obsolete
        assertSame(b, c.verifiedServiceOrNull())
    }

    @Test
    fun `disconnect during handshake fails the pending read once`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onDisconnected(g1)

        assertEquals(1, p.completions)
        assertTrue(p.single() is TopologyReadResult.BackendUnavailable)
        assertFalse(c.isVerified)

        c.onHandshakeResult(g1, a, 2) // obsolete
        assertFalse(c.isVerified)
    }

    // -------------------------------------------------- bounded replacement

    @Test
    fun `delayed remove-true disconnect does not disturb the verified replacement`() {
        val rec = Recorder()
        val c = started(rec)

        c.requestRead(ReadProbe().onResult)
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onHandshakeResult(g1, a, 1) // stale -> one replacement
        assertEquals(listOf(g1), rec.removes)
        val g2 = rec.binds.last()
        val b = verify(c, rec)

        c.onDisconnected(g1) // late remove=true disconnect for the stale gen

        assertSame(b, c.verifiedServiceOrNull())
        assertNull(c.versionMismatch)
        assertEquals(listOf(g1, g2), rec.binds)
        assertEquals(listOf(g1), rec.removes)
    }

    @Test
    fun `still-stale replacement fails closed with no second replacement`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onHandshakeResult(g1, a, 1) // replace once
        val g2 = rec.binds.last()
        val b = FakeService()
        c.onConnected(g2, b)
        c.onHandshakeResult(g2, b, 1) // still stale -> terminal

        assertEquals(UserServiceVersionMismatch(2, 1), c.versionMismatch)
        assertEquals(1, p.completions) // carried read failed once
        assertEquals(
            PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
            mismatchStatus(p.single()),
        )
        assertEquals(listOf(g1, g2), rec.binds)
        assertEquals(listOf(g1), rec.removes)

        val p2 = ReadProbe()
        c.requestRead(p2.onResult)
        assertEquals(
            PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
            mismatchStatus(p2.single()),
        )
    }

    @Test
    fun `unreported version replaces once then fails closed`() {
        val rec = Recorder()
        val c = started(rec)

        c.requestRead(ReadProbe().onResult)
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onHandshakeResult(g1, a, null) // cannot report -> replace
        val b = FakeService()
        c.onConnected(rec.binds.last(), b)
        c.onHandshakeResult(rec.binds.last(), b, null) // still cannot report -> terminal

        assertEquals(UserServiceVersionMismatch(2, null), c.versionMismatch)
        assertEquals(listOf(g1), rec.removes)
    }

    @Test
    fun `a queued read is carried through the replacement and served by the verified replacement`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult) // queued
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onHandshakeResult(g1, a, 1) // replace; read still carried
        assertTrue(rec.dispatches.isEmpty()) // never dispatched against the stale service
        val b = verify(c, rec) // replacement verifies -> queued read dispatched now

        assertEquals(1, rec.dispatches.size)
        assertSame(b, rec.dispatches.single().service)
        deliver(c, rec, 0)
        assertEquals(1, p.completions)
    }

    // ------------------------------------------------------- resize gating

    @Test
    fun `no verified service is exposed while binding, handshaking, replacing, or mismatched`() {
        val rec = Recorder()
        val c = started(rec)

        c.requestRead(ReadProbe().onResult) // Binding
        assertNull(c.verifiedServiceOrNull())

        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a) // Handshaking
        assertNull(c.verifiedServiceOrNull())

        c.onHandshakeResult(g1, a, 1) // Replacing
        assertNull(c.verifiedServiceOrNull())

        val b = FakeService()
        c.onConnected(rec.binds.last(), b)
        c.onHandshakeResult(rec.binds.last(), b, 1) // VersionMismatch
        assertNull(c.verifiedServiceOrNull())
    }

    @Test
    fun `verified service is exposed only while alive`() {
        val rec = Recorder()
        val c = started(rec)

        c.requestRead(ReadProbe().onResult)
        val a = verify(c, rec)
        assertSame(a, c.verifiedServiceOrNull())

        a.alive = false
        assertNull(c.verifiedServiceOrNull())
    }

    // ------------------------------------------------------ restart lifecycle

    @Test
    fun `start-verify-stop-start-verify uses a new generation and ignores old callbacks`() {
        val rec = Recorder()
        val c = started(rec) // lifecycle 1

        val p1 = ReadProbe()
        c.requestRead(p1.onResult)
        val g1 = rec.binds.last()
        val a = verify(c, rec)
        deliver(c, rec, 0)
        assertEquals(1, p1.completions)

        c.stop()
        assertFalse(c.isVerified)
        assertNull(c.verifiedServiceOrNull()) // no carry-over of the verified service

        c.start() // lifecycle 2
        val p2 = ReadProbe()
        c.requestRead(p2.onResult)
        val g2 = rec.binds.last()
        assertTrue(g2 > g1) // monotonic; never reset
        val b = verify(c, rec)
        deliver(c, rec, 1)
        assertEquals(1, p2.completions)
        assertSame(b, c.verifiedServiceOrNull())

        // Delayed lifecycle-1 callbacks must not affect lifecycle 2.
        c.onHandshakeResult(g1, a, 2)
        c.onDisconnected(g1)
        assertSame(b, c.verifiedServiceOrNull())
    }

    @Test
    fun `start is idempotent — no parallel generation or duplicate bind`() {
        val rec = Recorder()
        val c = TopologyConnectionCoordinator(expected, { s: FakeService -> s.alive }, rec)
        c.start()
        c.start() // no-op

        c.requestRead(ReadProbe().onResult)
        assertEquals(1, rec.binds.size)
    }

    @Test
    fun `stop is idempotent — no duplicate completion`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        verify(c, rec) // dispatched read in flight

        c.stop()
        c.stop() // no-op
        assertEquals(1, p.completions)
    }

    @Test
    fun `foreground lifecycle stop then start then a fresh read succeeds`() {
        val rec = Recorder()
        val c = started(rec)
        c.requestRead(ReadProbe().onResult)
        verify(c, rec)
        c.stop()

        c.start()
        val p = ReadProbe()
        c.requestRead(p.onResult)
        verify(c, rec)
        deliver(c, rec, rec.dispatches.lastIndex)
        assertEquals(1, p.completions)
        assertTrue(p.single() is TopologyReadResult.Success)
    }

    // ------------------------------------------- generation-owned read races

    @Test
    fun `dispatched read from a disconnected generation is failed once and its late success dropped`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        val g1 = rec.binds.last()
        verify(c, rec) // Verified(g1) -> dispatch[0]
        assertEquals(1, rec.dispatches.size)

        c.onDisconnected(g1) // invalidate BEFORE the worker returns
        assertEquals(1, p.completions)
        assertTrue(p.single() is TopologyReadResult.BackendUnavailable)

        // N+1 verifies and serves its own read.
        val p2 = ReadProbe()
        c.requestRead(p2.onResult)
        verify(c, rec) // dispatch[1]

        deliver(c, rec, 0, TopologyReadResult.Success("stale")) // late N result
        assertEquals(1, p.completions) // still once; stale NOT delivered

        deliver(c, rec, 1, TopologyReadResult.Success("fresh"))
        assertEquals(1, p2.completions)
        assertEquals("fresh", (p2.single() as TopologyReadResult.Success).filteredDump)
    }

    @Test
    fun `dispatched read from a binder-dead generation is failed once and its late success dropped`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        verify(c, rec)

        c.onBinderDied()
        assertEquals(1, p.completions)

        deliver(c, rec, 0, TopologyReadResult.Success("stale"))
        assertEquals(1, p.completions) // unchanged
    }

    @Test
    fun `dispatched read from a stopped session is failed once and its late success dropped`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        verify(c, rec)

        c.stop()
        assertEquals(1, p.completions)

        c.start()
        deliver(c, rec, 0, TopologyReadResult.Success("stale")) // lifecycle-1 read
        assertEquals(1, p.completions) // unchanged; not delivered into lifecycle 2
    }

    @Test
    fun `invalidation plus duplicate callbacks plus late worker yields exactly one completion`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        val g1 = rec.binds.last()
        verify(c, rec)

        c.onDisconnected(g1)
        c.onDisconnected(g1) // duplicate, obsolete
        c.onBinderDied()      // obsolete generation
        deliver(c, rec, 0, TopologyReadResult.Success("stale")) // late worker

        assertEquals(1, p.completions)
    }

    // ----------------------------------- dead-before-delivery (BLOCKING race)

    @Test
    fun `service dying before read delivery suppresses the stale success and fails once`() {
        val rec = Recorder()
        val c = started(rec)

        val p = ReadProbe()
        c.requestRead(p.onResult)
        val g1 = rec.binds.last()
        val a = verify(c, rec) // Verified(g1) -> dispatch[0]
        assertEquals(1, rec.dispatches.size)

        // Worker obtained Success, but the binder dies before delivery and the
        // death callback has NOT been processed yet.
        a.alive = false
        deliver(c, rec, 0, TopologyReadResult.Success("stale topology"))

        assertEquals(1, p.completions)
        assertEquals(
            PrivilegedBackendStatus.BINDER_DIED,
            (p.single() as TopologyReadResult.BackendUnavailable).status,
        )
        assertFalse(c.isVerified) // generation invalidated synchronously
        assertNull(c.verifiedServiceOrNull())

        // A later ACTUAL death/disconnect for g1 is inert.
        c.onBinderDied()
        c.onDisconnected(g1)
        assertEquals(1, p.completions)

        // A future read establishes a fresh generation.
        val bindsBefore = rec.binds.size
        c.requestRead(ReadProbe().onResult)
        assertEquals(bindsBefore + 1, rec.binds.size)
    }

    @Test
    fun `dead service at completion fails every other owned read exactly once`() {
        val rec = Recorder()
        val c = started(rec)

        val p1 = ReadProbe()
        c.requestRead(p1.onResult)
        val a = verify(c, rec) // Verified -> dispatch[0] for p1
        val p2 = ReadProbe()
        c.requestRead(p2.onResult) // Verified -> dispatch[1] for p2
        assertEquals(2, rec.dispatches.size)

        a.alive = false
        deliver(c, rec, 0, TopologyReadResult.Success("stale")) // discovers dead

        assertEquals(1, p1.completions)
        assertTrue(p1.single() is TopologyReadResult.BackendUnavailable)
        // p2's in-flight read was failed once by the synchronous invalidation.
        assertEquals(1, p2.completions)
        assertTrue(p2.single() is TopologyReadResult.BackendUnavailable)

        // The late worker completion of p2's read is now inert.
        deliver(c, rec, 1, TopologyReadResult.Success("stale2"))
        assertEquals(1, p2.completions)
    }

    // ----------------------------------- non-queuing read (requestReadNow)

    @Test
    fun `requestReadNow dispatches against a live verified service`() {
        val rec = Recorder()
        val c = started(rec)
        c.requestRead(ReadProbe().onResult) // bind + verify to reach Verified
        verify(c, rec)
        rec.dispatches.clear()

        val p = ReadProbe()
        c.requestReadNow(p.onResult)
        assertEquals(1, rec.dispatches.size)
        deliver(c, rec, 0)
        assertEquals(1, p.completions)
        assertTrue(p.single() is TopologyReadResult.Success)
    }

    @Test
    fun `requestReadNow fails immediately when verified service is dead, no queue, no replay`() {
        val rec = Recorder()
        val c = started(rec)
        c.requestRead(ReadProbe().onResult)
        val a = verify(c, rec)
        rec.dispatches.clear()

        // The verified binder died between the readiness gate and admission.
        a.alive = false
        val p = ReadProbe()
        c.requestReadNow(p.onResult)

        assertEquals(1, p.completions)
        assertEquals(
            PrivilegedBackendStatus.BINDER_DIED,
            (p.single() as TopologyReadResult.BackendUnavailable).status,
        )
        assertTrue(rec.dispatches.isEmpty()) // never dispatched
        assertFalse(c.isVerified) // generation invalidated

        // The backend independently reacquires and a NEW service verifies.
        c.beginAcquisitionIfIdle()
        val b = verify(c, rec)
        assertSame(b, c.verifiedServiceOrNull())
        // The OLD request is permanently dead — not replayed by the reconnect.
        assertEquals(1, p.completions)

        // A NEW non-queuing read now dispatches against the new service.
        rec.dispatches.clear()
        val p2 = ReadProbe()
        c.requestReadNow(p2.onResult)
        assertEquals(1, rec.dispatches.size)
    }

    @Test
    fun `requestReadNow fails immediately in Disconnected and starts no acquisition`() {
        val rec = Recorder()
        val c = started(rec) // state is Disconnected, nothing bound

        val p = ReadProbe()
        c.requestReadNow(p.onResult)

        assertEquals(1, p.completions)
        assertTrue(p.single() is TopologyReadResult.BackendUnavailable)
        assertTrue(rec.binds.isEmpty()) // no bind/acquire
        assertTrue(rec.dispatches.isEmpty())
    }

    @Test
    fun `requestReadNow fails immediately while binding or handshaking`() {
        val rec = Recorder()
        val c = started(rec)
        c.requestRead(ReadProbe().onResult) // Binding
        val pBinding = ReadProbe()
        c.requestReadNow(pBinding.onResult)
        assertEquals(
            PrivilegedBackendStatus.CONNECTING,
            (pBinding.single() as TopologyReadResult.BackendUnavailable).status,
        )

        val g = rec.binds.last()
        val a = FakeService()
        c.onConnected(g, a) // Handshaking
        val pHandshaking = ReadProbe()
        c.requestReadNow(pHandshaking.onResult)
        assertEquals(
            PrivilegedBackendStatus.CONNECTING,
            (pHandshaking.single() as TopologyReadResult.BackendUnavailable).status,
        )
        assertTrue(rec.dispatches.isEmpty())
    }

    @Test
    fun `requestReadNow fails immediately while replacing and on version mismatch`() {
        val rec = Recorder()
        val c = started(rec)
        c.requestRead(ReadProbe().onResult)
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onHandshakeResult(g1, a, 1) // stale -> Replacing
        val pReplacing = ReadProbe()
        c.requestReadNow(pReplacing.onResult)
        assertEquals(
            PrivilegedBackendStatus.CONNECTING,
            (pReplacing.single() as TopologyReadResult.BackendUnavailable).status,
        )

        val g2 = rec.binds.last()
        val b = FakeService()
        c.onConnected(g2, b)
        c.onHandshakeResult(g2, b, 1) // still stale -> VersionMismatch
        val pMismatch = ReadProbe()
        c.requestReadNow(pMismatch.onResult)
        assertEquals(
            PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
            (pMismatch.single() as TopologyReadResult.BackendUnavailable).status,
        )
        assertTrue(rec.dispatches.isEmpty())
    }

    @Test
    fun `requestReadNow post-dispatch binder death fails once and discards the stale success`() {
        val rec = Recorder()
        val c = started(rec)
        c.requestRead(ReadProbe().onResult)
        val a = verify(c, rec)
        rec.dispatches.clear()

        val p = ReadProbe()
        c.requestReadNow(p.onResult) // admitted, dispatched
        assertEquals(1, rec.dispatches.size)

        // Binder dies before the worker result is delivered.
        a.alive = false
        deliver(c, rec, 0, TopologyReadResult.Success("stale"))

        assertEquals(1, p.completions)
        assertTrue(p.single() is TopologyReadResult.BackendUnavailable)
        assertFalse(c.isVerified)

        // Reconnect; the old read is not resurrected.
        c.beginAcquisitionIfIdle()
        verify(c, rec)
        assertEquals(1, p.completions)
    }

    // ------------------------------------------------------ misc invariants

    @Test
    fun `binder death after mismatch clears it and restores the budget for a new cycle`() {
        val rec = Recorder()
        val c = started(rec)

        c.requestRead(ReadProbe().onResult)
        val g1 = rec.binds.last()
        val a = FakeService()
        c.onConnected(g1, a)
        c.onHandshakeResult(g1, a, 1)
        val b = FakeService()
        c.onConnected(rec.binds.last(), b)
        c.onHandshakeResult(rec.binds.last(), b, 1) // terminal mismatch
        assertTrue(c.versionMismatch != null)

        c.onBinderDied()
        assertNull(c.versionMismatch)

        c.requestRead(ReadProbe().onResult)
        val g3 = rec.binds.last()
        val d = FakeService()
        c.onConnected(g3, d)
        c.onHandshakeResult(g3, d, 1)
        assertEquals(g3, rec.removes.last()) // replacement allowed again in the new cycle
    }

    // ------------------------------------------------------ status precedence

    @Test
    fun `live base status wins over a recorded mismatch or verification`() {
        // A live permission/binder condition is returned even if a mismatch or
        // verification is recorded — the base is resolved first.
        assertEquals(
            PrivilegedBackendStatus.PERMISSION_DENIED,
            TopologyConnectionCoordinator.effectiveStatus(
                PrivilegedBackendStatus.PERMISSION_DENIED, versionMismatch = true, verified = false,
            ),
        )
        assertEquals(
            PrivilegedBackendStatus.BINDER_DIED,
            TopologyConnectionCoordinator.effectiveStatus(
                PrivilegedBackendStatus.BINDER_DIED, versionMismatch = false, verified = true,
            ),
        )
        // Base READY-capable -> coordinator state decides.
        assertEquals(
            PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
            TopologyConnectionCoordinator.effectiveStatus(
                PrivilegedBackendStatus.READY, versionMismatch = true, verified = false,
            ),
        )
        assertEquals(
            PrivilegedBackendStatus.READY,
            TopologyConnectionCoordinator.effectiveStatus(
                PrivilegedBackendStatus.READY, versionMismatch = false, verified = true,
            ),
        )
        assertEquals(
            PrivilegedBackendStatus.CONNECTING,
            TopologyConnectionCoordinator.effectiveStatus(
                PrivilegedBackendStatus.READY, versionMismatch = false, verified = false,
            ),
        )
    }
}

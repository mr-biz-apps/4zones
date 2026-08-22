package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShortcutBackendStatusStoreTest {

    @Before
    fun setUp() = resetStore()

    @After
    fun tearDown() = resetStore()

    private fun resetStore() {
        // The store is process-global; keep tests independent of each other and
        // of the wider suite. clear() with no listener is a silent reset.
        ShortcutBackendStatusStore.setListener(null)
        ShortcutBackendStatusStore.clear()
    }

    @Test
    fun `initial and reset state is null`() {
        assertNull(ShortcutBackendStatusStore.latest())
    }

    @Test
    fun `publish stores the status and notifies the listener`() {
        val seen = mutableListOf<PrivilegedBackendStatus?>()
        ShortcutBackendStatusStore.setListener { seen += it }

        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.READY)

        assertEquals(PrivilegedBackendStatus.READY, ShortcutBackendStatusStore.latest())
        assertEquals(listOf<PrivilegedBackendStatus?>(PrivilegedBackendStatus.READY), seen)
    }

    @Test
    fun `latest survives without a listener`() {
        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.CONNECTING)

        assertEquals(PrivilegedBackendStatus.CONNECTING, ShortcutBackendStatusStore.latest())
    }

    @Test
    fun `clear nulls the status and notifies the listener with null`() {
        val seen = mutableListOf<PrivilegedBackendStatus?>()
        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.READY)
        ShortcutBackendStatusStore.setListener { seen += it }

        ShortcutBackendStatusStore.clear()

        assertNull(ShortcutBackendStatusStore.latest())
        assertEquals(listOf<PrivilegedBackendStatus?>(null), seen)
    }

    @Test
    fun `removed listener receives no later publish or clear`() {
        val seen = mutableListOf<PrivilegedBackendStatus?>()
        ShortcutBackendStatusStore.setListener { seen += it }
        ShortcutBackendStatusStore.setListener(null)

        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.READY)
        ShortcutBackendStatusStore.clear()

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `replacing the listener routes later publications only to the replacement`() {
        val seenA = mutableListOf<PrivilegedBackendStatus?>()
        val seenB = mutableListOf<PrivilegedBackendStatus?>()
        ShortcutBackendStatusStore.setListener { seenA += it }
        ShortcutBackendStatusStore.setListener { seenB += it }

        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.READY)

        assertTrue(seenA.isEmpty())
        assertEquals(listOf<PrivilegedBackendStatus?>(PrivilegedBackendStatus.READY), seenB)
    }

    @Test
    fun `publication sequence is observed in order including the final null`() {
        val seen = mutableListOf<PrivilegedBackendStatus?>()
        ShortcutBackendStatusStore.setListener { seen += it }

        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.BINDER_UNAVAILABLE)
        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.CONNECTING)
        ShortcutBackendStatusStore.publish(PrivilegedBackendStatus.READY)
        ShortcutBackendStatusStore.clear()

        assertEquals(
            listOf(
                PrivilegedBackendStatus.BINDER_UNAVAILABLE,
                PrivilegedBackendStatus.CONNECTING,
                PrivilegedBackendStatus.READY,
                null,
            ),
            seen,
        )
        assertNull(ShortcutBackendStatusStore.latest())
    }
}

package uk.mr_biz.fourzones.shortcut

import android.view.KeyEvent
import uk.mr_biz.fourzones.geometry.Quadrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure consumption state-machine coverage. The submit seam mirrors the
 * production dispatcher (match → map → submit): it records a submitted quadrant
 * ONLY when the real matcher matches, and returns the matched candidate. No
 * AccessibilityService, no snap engine, no framework runtime (inlined KeyEvent
 * constants only).
 */
class ShortcutKeyConsumptionTrackerTest {

    private val submitted = mutableListOf<Quadrant>()

    private fun newTracker() =
        ShortcutKeyConsumptionTracker { action, keyCode, repeat, alt, meta, ctrl, shift ->
            val candidate = QuadrantShortcutMatcher.match(
                action = action,
                keyCode = keyCode,
                repeatCount = repeat,
                altPressed = alt,
                metaPressed = meta,
                ctrlPressed = ctrl,
                shiftPressed = shift,
            )
            if (candidate != null) submitted += QuadrantShortcutMapping.toQuadrant(candidate)
            candidate
        }

    private fun ShortcutKeyConsumptionTracker.down(
        keyCode: Int,
        repeat: Int = 0,
        deviceId: Int = 7,
        alt: Boolean = true,
        meta: Boolean = true,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) = onKeyEvent(
        deviceId = deviceId,
        action = KeyEvent.ACTION_DOWN,
        keyCode = keyCode,
        repeatCount = repeat,
        altPressed = alt,
        metaPressed = meta,
        ctrlPressed = ctrl,
        shiftPressed = shift,
    )

    private fun ShortcutKeyConsumptionTracker.up(keyCode: Int, deviceId: Int = 7) =
        onKeyEvent(
            deviceId = deviceId,
            action = KeyEvent.ACTION_UP,
            keyCode = keyCode,
            repeatCount = 0,
            altPressed = false,
            metaPressed = false,
            ctrlPressed = false,
            shiftPressed = false,
        )

    // A. matched stream: DOWN submits once + consume; repeat consume no submit; UP consume.
    @Test
    fun `matched digit stream submits once and consumes down, repeat, and up`() {
        val t = newTracker()

        val d = t.down(KeyEvent.KEYCODE_1)
        assertEquals(ShortcutCandidate.ONE, d.candidate)
        assertTrue(d.consume)
        assertEquals(listOf(Quadrant.TOP_LEFT), submitted)

        val r = t.down(KeyEvent.KEYCODE_1, repeat = 1)
        assertTrue(r.consume)
        assertNull(r.candidate)
        assertEquals(listOf(Quadrant.TOP_LEFT), submitted) // no resubmit

        val u = t.up(KeyEvent.KEYCODE_1)
        assertTrue(u.consume)
        assertEquals(listOf(Quadrant.TOP_LEFT), submitted)
    }

    // B. holding produces only one mutation request.
    @Test
    fun `holding the digit yields only one submitted request`() {
        val t = newTracker()
        t.down(KeyEvent.KEYCODE_2)
        repeat(5) { t.down(KeyEvent.KEYCODE_2, repeat = it + 1) }
        assertEquals(listOf(Quadrant.TOP_RIGHT), submitted)
    }

    // C. modifier release ordering: UP without modifiers still consumed.
    @Test
    fun `owned digit UP is consumed even after modifiers were released`() {
        val t = newTracker()
        t.down(KeyEvent.KEYCODE_3) // Alt+Meta held
        // UP arrives with no modifiers (already released): still owned.
        val u = t.onKeyEvent(
            deviceId = 7,
            action = KeyEvent.ACTION_UP,
            keyCode = KeyEvent.KEYCODE_3,
            repeatCount = 0,
            altPressed = false,
            metaPressed = false,
            ctrlPressed = false,
            shiftPressed = false,
        )
        assertTrue(u.consume)
    }

    // D. unmatched keys are never consumed.
    @Test
    fun `unmatched keys are not consumed`() {
        val t = newTracker()
        assertFalse(t.down(KeyEvent.KEYCODE_1, alt = false, meta = false).consume) // 1 alone
        assertFalse(t.down(KeyEvent.KEYCODE_1, meta = false).consume) // Alt+1
        assertFalse(t.down(KeyEvent.KEYCODE_1, alt = false).consume) // Meta+1
        assertFalse(t.down(KeyEvent.KEYCODE_5).consume) // Alt+Meta+5
        assertFalse(t.down(KeyEvent.KEYCODE_A).consume) // letter
        assertFalse(t.down(KeyEvent.KEYCODE_NUMPAD_1).consume) // numpad
        assertTrue(submitted.isEmpty())
        // An unmatched key's UP is also not consumed.
        assertFalse(t.up(KeyEvent.KEYCODE_5).consume)
    }

    // Alt/Meta key events themselves are unmatched and unconsumed.
    @Test
    fun `alt and meta key events themselves are not consumed`() {
        val t = newTracker()
        assertFalse(t.down(KeyEvent.KEYCODE_ALT_LEFT).consume)
        assertFalse(t.down(KeyEvent.KEYCODE_META_LEFT).consume)
        assertFalse(t.up(KeyEvent.KEYCODE_ALT_LEFT).consume)
        assertFalse(t.up(KeyEvent.KEYCODE_META_LEFT).consume)
    }

    // REBIND-1, through the REAL tracker → REAL matcher path. A chord carrying
    // Ctrl or Shift is not matched, so it is NOT consumed and NOTHING is
    // submitted: the keystroke reaches the focused app untouched, which is what
    // keeps Ctrl+Win+1..4 usable for its reserved future meaning. Because alt
    // and ctrl are no longer interchangeable, a transposed argument between the
    // tracker and the matcher would also fail here.
    @Test
    fun `strict modifiers - ctrl or shift chords are neither consumed nor submitted`() {
        val t = newTracker()
        // Ctrl+Alt+Win+1..4 — the reserved-chord collision case.
        assertFalse(t.down(KeyEvent.KEYCODE_1, ctrl = true).consume)
        assertFalse(t.down(KeyEvent.KEYCODE_2, ctrl = true).consume)
        assertFalse(t.down(KeyEvent.KEYCODE_3, ctrl = true).consume)
        assertFalse(t.down(KeyEvent.KEYCODE_4, ctrl = true).consume)
        // The OLD chord, Ctrl+Win+1..4.
        assertFalse(t.down(KeyEvent.KEYCODE_1, alt = false, ctrl = true).consume)
        // Shift+Alt+Win+1.
        assertFalse(t.down(KeyEvent.KEYCODE_1, shift = true).consume)
        assertTrue(submitted.isEmpty())
        // Unconsumed streams are unowned, so their UPs pass through too.
        assertFalse(t.up(KeyEvent.KEYCODE_1).consume)
    }

    // E. simultaneous matched digits are independently owned.
    @Test
    fun `simultaneous matched digits are independently owned and consumed`() {
        val t = newTracker()
        assertTrue(t.down(KeyEvent.KEYCODE_1).consume)
        assertTrue(t.down(KeyEvent.KEYCODE_2).consume)
        assertEquals(listOf(Quadrant.TOP_LEFT, Quadrant.TOP_RIGHT), submitted)

        assertTrue(t.up(KeyEvent.KEYCODE_2).consume)
        assertTrue(t.up(KeyEvent.KEYCODE_1).consume)
        // Releasing one does not release the other prematurely (both were owned).
    }

    // Different devices pressing the same digit are distinct streams.
    @Test
    fun `same digit on different devices are distinct streams`() {
        val t = newTracker()
        assertTrue(t.down(KeyEvent.KEYCODE_1, deviceId = 7).consume)
        assertTrue(t.down(KeyEvent.KEYCODE_1, deviceId = 8).consume)
        assertTrue(t.up(KeyEvent.KEYCODE_1, deviceId = 7).consume)
        // Device 8's stream is still owned until its own UP.
        assertTrue(t.up(KeyEvent.KEYCODE_1, deviceId = 8).consume)
    }

    // F/G/H. consumption is independent of the snap result (Busy/PrivilegeUnavailable/NoTarget).
    // The tracker consumes based on MATCH at DOWN; the seam returns the matched
    // candidate regardless of any downstream outcome, so the stream is consumed.
    @Test
    fun `consumption is independent of the snap outcome`() {
        // A seam that always "matches" 1..4 (simulating submission) regardless of
        // whether the eventual snap result is Busy/PrivilegeUnavailable/NoTarget.
        var submits = 0
        val t = ShortcutKeyConsumptionTracker { action, keyCode, repeat, alt, meta, ctrl, shift ->
            val c = QuadrantShortcutMatcher.match(
                action = action,
                keyCode = keyCode,
                repeatCount = repeat,
                altPressed = alt,
                metaPressed = meta,
                ctrlPressed = ctrl,
                shiftPressed = shift,
            )
            if (c != null) submits++
            c
        }
        // Matched chord whose snap would return e.g. Busy/PrivilegeUnavailable/NoTarget
        // downstream — the tracker still consumes its whole stream.
        assertTrue(t.down(KeyEvent.KEYCODE_2).consume)
        assertTrue(t.down(KeyEvent.KEYCODE_2, repeat = 1).consume)
        assertTrue(t.up(KeyEvent.KEYCODE_2).consume)
        assertEquals(1, submits)
    }

    // BLOCKER 1: a duplicate initial-looking DOWN of an owned stream must not resubmit.
    @Test
    fun `a duplicate repeat-zero down on an owned stream consumes without resubmitting`() {
        val t = newTracker()

        assertTrue(t.down(KeyEvent.KEYCODE_1).consume) // owns + submits once
        val dup = t.down(KeyEvent.KEYCODE_1) // duplicate repeat=0 for the owned stream
        assertTrue(dup.consume)
        assertNull(dup.candidate)
        assertEquals(listOf(Quadrant.TOP_LEFT), submitted) // still ONE submission

        assertTrue(t.up(KeyEvent.KEYCODE_1).consume) // releases ownership

        // A later fresh press submits normally again.
        assertTrue(t.down(KeyEvent.KEYCODE_1).consume)
        assertEquals(listOf(Quadrant.TOP_LEFT, Quadrant.TOP_LEFT), submitted)
    }

    // I. lifecycle reset forgets ownership; a later stray UP is not consumed.
    @Test
    fun `reset forgets ownership so a later up is not consumed`() {
        val t = newTracker()
        t.down(KeyEvent.KEYCODE_1) // owned

        t.reset()

        val u = t.up(KeyEvent.KEYCODE_1) // ownership forgotten
        assertFalse(u.consume)
    }

    // BLOCKER 2 (tracker side): after reset, orphan repeat/UP are not consumed and
    // a new complete chord recovers normally.
    @Test
    fun `after reset orphan repeat and up are not consumed and a new chord recovers`() {
        val t = newTracker()
        assertTrue(t.down(KeyEvent.KEYCODE_1).consume) // owned, one submit
        assertEquals(listOf(Quadrant.TOP_LEFT), submitted)

        t.reset() // interruption

        // Stale ownership is gone: the held key's repeat and its UP pass through.
        assertFalse(t.down(KeyEvent.KEYCODE_1, repeat = 1).consume)
        assertFalse(t.up(KeyEvent.KEYCODE_1).consume)

        // A NEW complete matched chord works again.
        assertTrue(t.down(KeyEvent.KEYCODE_1).consume)
        assertEquals(listOf(Quadrant.TOP_LEFT, Quadrant.TOP_LEFT), submitted)
    }

    @Test
    fun `reset clears all currently owned streams`() {
        val t = newTracker()
        t.down(KeyEvent.KEYCODE_1)
        t.down(KeyEvent.KEYCODE_2)

        t.reset()

        assertFalse(t.up(KeyEvent.KEYCODE_1).consume)
        assertFalse(t.up(KeyEvent.KEYCODE_2).consume)
    }

    @Test
    fun `reset is idempotent and harmless with nothing owned`() {
        val t = newTracker()
        t.reset()
        t.reset()
        // A subsequent matched chord still works.
        assertTrue(t.down(KeyEvent.KEYCODE_3).consume)
        assertEquals(listOf(Quadrant.BOTTOM_LEFT), submitted)
    }

    // J. a duplicate/second initial DOWN of an already-owned key does not double-submit
    //    (repeat==0 re-DOWN without an intervening UP still re-matches once; but a
    //    real auto-repeat uses repeat>0 which never resubmits — covered in A/B).
    @Test
    fun `a repeat down never resubmits even if it arrives many times`() {
        val t = newTracker()
        t.down(KeyEvent.KEYCODE_4)
        t.down(KeyEvent.KEYCODE_4, repeat = 1)
        t.down(KeyEvent.KEYCODE_4, repeat = 2)
        assertEquals(listOf(Quadrant.BOTTOM_RIGHT), submitted)
    }
}

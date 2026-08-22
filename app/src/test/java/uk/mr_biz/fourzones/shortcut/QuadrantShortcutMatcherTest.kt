package uk.mr_biz.fourzones.shortcut

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure matcher coverage — no AccessibilityService, no live KeyEvent. Only
 * compile-time KeyEvent constants are used (they inline into the test), so no
 * Android runtime is needed. The matcher's Alt/Meta/Ctrl/Shift inputs are the
 * PUBLIC pressed-states, which the framework already collapses across left/right
 * variants, so no raw meta-state bit logic is copied here.
 *
 * REBIND-1: the chord is Alt + Win + 1..4 and the match is STRICT — exactly
 * {Alt, Meta}. Ctrl or Shift present REJECTS. The collision case
 * `Ctrl + Alt + Win + N` is the one that matters most: `Ctrl + Win + 1..4` is
 * reserved for a different future meaning, so the two chords must never both be
 * able to fire.
 */
class QuadrantShortcutMatcherTest {

    private fun match(
        keyCode: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeat: Int = 0,
        alt: Boolean = true,
        meta: Boolean = true,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) = QuadrantShortcutMatcher.match(
        action = action,
        keyCode = keyCode,
        repeatCount = repeat,
        altPressed = alt,
        metaPressed = meta,
        ctrlPressed = ctrl,
        shiftPressed = shift,
    )

    // ---- accepted: Alt+Meta+1..4 DOWN repeat=0, Ctrl and Shift absent

    @Test
    fun `alt meta 1 down maps to ONE (TL)`() {
        val c = match(KeyEvent.KEYCODE_1)
        assertEquals(ShortcutCandidate.ONE, c)
        assertEquals("TL", c!!.intendedMeaning)
    }

    @Test
    fun `alt meta 2 down maps to TWO (TR)`() {
        assertEquals(ShortcutCandidate.TWO, match(KeyEvent.KEYCODE_2))
        assertEquals("TR", ShortcutCandidate.TWO.intendedMeaning)
    }

    @Test
    fun `alt meta 3 down maps to THREE (BL)`() {
        assertEquals(ShortcutCandidate.THREE, match(KeyEvent.KEYCODE_3))
        assertEquals("BL", ShortcutCandidate.THREE.intendedMeaning)
    }

    @Test
    fun `alt meta 4 down maps to FOUR (BR)`() {
        assertEquals(ShortcutCandidate.FOUR, match(KeyEvent.KEYCODE_4))
        assertEquals("BR", ShortcutCandidate.FOUR.intendedMeaning)
    }

    // ---- REBIND-1 COLLISION CASE: Ctrl+Alt+Win+N must NEVER fire a snap.
    // Ctrl+Win+1..4 is reserved for desktop switching; if Ctrl+Alt+Win+N also
    // matched, both chords could fire from one physical press.

    @Test
    fun `ctrl alt meta 1 to 4 is rejected (reserved-chord collision)`() {
        assertNull(match(KeyEvent.KEYCODE_1, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_2, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_3, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_4, ctrl = true))
    }

    // ---- REBIND-1: the OLD chord must no longer fire.

    @Test
    fun `the old ctrl meta chord no longer matches`() {
        assertNull(match(KeyEvent.KEYCODE_1, alt = false, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_2, alt = false, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_3, alt = false, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_4, alt = false, ctrl = true))
    }

    // ---- REBIND-1: Shift present rejects, keeping Shift+Alt+Win free for a
    // distinct future chord rather than a silent alias of the snap chord.

    @Test
    fun `shift alt meta 1 to 4 is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, shift = true))
        assertNull(match(KeyEvent.KEYCODE_2, shift = true))
        assertNull(match(KeyEvent.KEYCODE_3, shift = true))
        assertNull(match(KeyEvent.KEYCODE_4, shift = true))
    }

    @Test
    fun `ctrl and shift together on the alt meta chord is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, ctrl = true, shift = true))
    }

    // ---- rejected: missing a required modifier

    @Test
    fun `digit alone is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, alt = false, meta = false))
    }

    @Test
    fun `alt without meta is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, alt = true, meta = false))
    }

    @Test
    fun `meta without alt is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, alt = false, meta = true))
    }

    @Test
    fun `ctrl alone with a digit is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, alt = false, meta = false, ctrl = true))
    }

    @Test
    fun `shift alone with a digit is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, alt = false, meta = false, shift = true))
    }

    // ---- rejected: wrong key

    @Test
    fun `unrelated digits are rejected`() {
        assertNull(match(KeyEvent.KEYCODE_5))
        assertNull(match(KeyEvent.KEYCODE_0))
    }

    @Test
    fun `numpad digits are not matched in this phase`() {
        assertNull(match(KeyEvent.KEYCODE_NUMPAD_1))
        assertNull(match(KeyEvent.KEYCODE_NUMPAD_2))
    }

    @Test
    fun `letters are rejected`() {
        assertNull(match(KeyEvent.KEYCODE_A))
        assertNull(match(KeyEvent.KEYCODE_W))
    }

    // ---- rejected: wrong action / repeat

    @Test
    fun `action up is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, action = KeyEvent.ACTION_UP))
    }

    @Test
    fun `auto-repeat down is rejected`() {
        assertNull(match(KeyEvent.KEYCODE_1, repeat = 1))
        assertNull(match(KeyEvent.KEYCODE_2, repeat = 5))
    }

    // ---- exhaustive modifier truth table over the four booleans: EXACTLY
    // {alt, meta} matches, all fifteen other combinations do not. This is the
    // oracle that would catch a re-permissive predicate or a transposed
    // parameter anywhere in the matcher.

    @Test
    fun `exactly alt plus meta matches and no other modifier combination does`() {
        for (alt in listOf(false, true)) {
            for (meta in listOf(false, true)) {
                for (ctrl in listOf(false, true)) {
                    for (shift in listOf(false, true)) {
                        val expected =
                            if (alt && meta && !ctrl && !shift) ShortcutCandidate.ONE else null
                        assertEquals(
                            "alt=$alt meta=$meta ctrl=$ctrl shift=$shift",
                            expected,
                            match(KeyEvent.KEYCODE_1, alt = alt, meta = meta, ctrl = ctrl, shift = shift),
                        )
                    }
                }
            }
        }
    }
}

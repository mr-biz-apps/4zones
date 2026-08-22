package uk.mr_biz.fourzones.shortcut

import android.view.KeyEvent
import uk.mr_biz.fourzones.geometry.Quadrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves which key events trigger exactly one snap request, without the
 * AccessibilityService. Uses only inlined KeyEvent constants (no framework
 * runtime). The mapping and matcher are exercised through the real dispatcher.
 */
class ShortcutSnapDispatcherTest {

    private val requested = mutableListOf<Quadrant>()
    private val dispatcher = ShortcutSnapDispatcher { requested += it }

    private fun handle(
        keyCode: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeat: Int = 0,
        alt: Boolean = true,
        meta: Boolean = true,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) = dispatcher.handle(
        action = action,
        keyCode = keyCode,
        repeatCount = repeat,
        altPressed = alt,
        metaPressed = meta,
        ctrlPressed = ctrl,
        shiftPressed = shift,
    )

    @Test
    fun `matched initial key-down submits exactly one request per quadrant`() {
        assertEquals(ShortcutCandidate.ONE, handle(KeyEvent.KEYCODE_1))
        assertEquals(ShortcutCandidate.TWO, handle(KeyEvent.KEYCODE_2))
        assertEquals(ShortcutCandidate.THREE, handle(KeyEvent.KEYCODE_3))
        assertEquals(ShortcutCandidate.FOUR, handle(KeyEvent.KEYCODE_4))
        assertEquals(
            listOf(
                Quadrant.TOP_LEFT,
                Quadrant.TOP_RIGHT,
                Quadrant.BOTTOM_LEFT,
                Quadrant.BOTTOM_RIGHT,
            ),
            requested,
        )
    }

    @Test
    fun `ACTION_UP submits nothing`() {
        assertNull(handle(KeyEvent.KEYCODE_1, action = KeyEvent.ACTION_UP))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `auto-repeat submits nothing`() {
        assertNull(handle(KeyEvent.KEYCODE_1, repeat = 1))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `unmatched key submits nothing`() {
        assertNull(handle(KeyEvent.KEYCODE_5))
        assertNull(handle(KeyEvent.KEYCODE_A))
        assertNull(handle(KeyEvent.KEYCODE_1, alt = false)) // no alt
        assertNull(handle(KeyEvent.KEYCODE_1, meta = false)) // no meta
        assertTrue(requested.isEmpty())
    }

    // REBIND-1, through the REAL dispatcher → REAL matcher path: the strict
    // predicate and the parameter ORDER between dispatcher and matcher are both
    // exercised here (alt and ctrl are no longer interchangeable, so a
    // transposition of the two would fail this test).
    @Test
    fun `strict modifiers - ctrl or shift on the alt meta chord submits nothing`() {
        assertNull(handle(KeyEvent.KEYCODE_1, ctrl = true)) // Ctrl+Alt+Win+1: collision case
        assertNull(handle(KeyEvent.KEYCODE_2, ctrl = true))
        assertNull(handle(KeyEvent.KEYCODE_3, ctrl = true))
        assertNull(handle(KeyEvent.KEYCODE_4, ctrl = true))
        assertNull(handle(KeyEvent.KEYCODE_1, shift = true)) // Shift+Alt+Win+1
        assertNull(handle(KeyEvent.KEYCODE_1, alt = false, ctrl = true)) // the OLD chord
        assertTrue(requested.isEmpty())
    }
}

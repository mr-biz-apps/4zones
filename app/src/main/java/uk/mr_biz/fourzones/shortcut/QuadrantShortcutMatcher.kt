package uk.mr_biz.fourzones.shortcut

import android.view.KeyEvent

/**
 * A recognized quadrant shortcut candidate. ONE..FOUR are the top-row digit
 * chords. A matched candidate DOES snap: it is mapped to a quadrant by
 * QuadrantShortcutMapping and driven through ShortcutSnapDispatcher into the
 * snap transaction, which ends in a privileged task resize.
 *
 * [intendedMeaning] is a human-readable label (1→TL, 2→TR, 3→BL, 4→BR) recorded
 * for diagnostics only — it is read by a log line and a UI label, and never by
 * the mapping that actually drives the snap.
 */
enum class ShortcutCandidate(val digitKeyCode: Int, val intendedMeaning: String) {
    ONE(KeyEvent.KEYCODE_1, "TL"),
    TWO(KeyEvent.KEYCODE_2, "TR"),
    THREE(KeyEvent.KEYCODE_3, "BL"),
    FOUR(KeyEvent.KEYCODE_4, "BR"),
}

/**
 * Pure matcher for the global quadrant chord Alt + Meta(Win) + top-row digit.
 *
 * It is separated from the AccessibilityService so the recognition POLICY is
 * unit-testable without Android plumbing: it takes the already-extracted
 * KeyEvent facts (action, keyCode, repeatCount, and the PUBLIC Alt/Meta/Ctrl/
 * Shift pressed-states) — never a live KeyEvent. Only compile-time KeyEvent
 * constants are referenced, so the tests need no framework runtime.
 *
 * Policy — STRICT MODIFIER MATCHING (REBIND-1):
 *  - only the initial press: `action == ACTION_DOWN` and `repeatCount == 0`
 *    (never on auto-repeat, never on ACTION_UP);
 *  - Alt AND Meta must BOTH be pressed. Left/right variants are equivalent via
 *    the public pressed-state semantics; this matcher never distinguishes them;
 *  - Ctrl present REJECTS the chord, and Shift present REJECTS the chord. The
 *    match is therefore EXACTLY {Alt, Meta} — no superset matches. This is not
 *    decoration:
 *      * `Ctrl + Win + 1..4` is reserved for a DIFFERENT future meaning
 *        (desktop/workspace switching), so `Ctrl + Alt + Win + 1` must be
 *        INCAPABLE of firing a snap — the two chords can never both fire;
 *      * rejecting Shift keeps `Shift + Alt + Win + 1..4` available as a
 *        distinct future chord rather than making it a silent alias of the
 *        snap chord.
 *    The previous predicate (`!ctrlPressed || !metaPressed`) did not exclude
 *    other modifiers, so `Ctrl + Alt + Win + 1` matched. That was a latent
 *    collision and it does not survive this rebind;
 *  - the keyCode must be a TOP-ROW digit 1..4. Numpad digits are different
 *    keyCodes and are intentionally NOT matched in this phase.
 */
object QuadrantShortcutMatcher {

    fun match(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        altPressed: Boolean,
        metaPressed: Boolean,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
    ): ShortcutCandidate? {
        if (action != KeyEvent.ACTION_DOWN) return null
        if (repeatCount != 0) return null
        // STRICT modifier match (REBIND-1). Alt AND Meta must both be pressed…
        if (!altPressed || !metaPressed) return null
        // …and Ctrl or Shift present REJECTS the chord, so the accepted modifier
        // set is EXACTLY {Alt, Meta} and no superset (notably Ctrl+Alt+Win+N) matches.
        if (ctrlPressed || shiftPressed) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_1 -> ShortcutCandidate.ONE
            KeyEvent.KEYCODE_2 -> ShortcutCandidate.TWO
            KeyEvent.KEYCODE_3 -> ShortcutCandidate.THREE
            KeyEvent.KEYCODE_4 -> ShortcutCandidate.FOUR
            else -> null
        }
    }
}

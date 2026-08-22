package uk.mr_biz.fourzones.shortcut

import android.view.KeyEvent

/** Per-event decision: the matched candidate (for logging) and whether to consume. */
data class ShortcutKeyDecision(val candidate: ShortcutCandidate?, val consume: Boolean)

/**
 * Pure state machine deciding whether the AccessibilityService should CONSUME a
 * key event (reserve the DexZones chord so the focused app / DeX does not also
 * act on it) and whether it should submit exactly one snap request.
 *
 * Phase 3B2 is INPUT PROPAGATION ONLY — it changes nothing about the (already
 * hardware-validated) snap transaction. It reserves only the matched digit
 * stream:
 *
 *  - ownership is checked FIRST for EVERY DOWN: any DOWN of an already-owned
 *    stream is consumed WITHOUT resubmitting (regardless of repeatCount), so a
 *    duplicate initial-looking `repeat==0` DOWN cannot submit twice;
 *  - only an UNOWNED initial matched DOWN (repeat==0, Alt+Meta+1..4 with Ctrl
 *    and Shift ABSENT) submits exactly one request (via [submitMatchedDown],
 *    which returns the candidate iff it matched) and marks that physical digit
 *    stream owned → consume;
 *  - the ACTION_UP of an owned stream is consumed and releases ownership — even
 *    if Alt/Meta were already released (ownership is tracked from the accepted
 *    DOWN; modifiers are NOT rematched on UP);
 *  - everything else (unmatched keys, Alt/Meta events themselves, non-owned
 *    up/repeat) is NOT consumed.
 *
 * Ownership is decided at DOWN time by MATCHING, never by mutation success — so a
 * chord whose snap returns Busy / PrivilegeUnavailable / NoTarget is STILL
 * consumed (otherwise a failed DexZones command would leak to the focused app).
 *
 * Multiple simultaneous matched digits are independently owned via an active set
 * keyed by [deviceId] + keyCode. That key is EPHEMERAL transient stream
 * correlation only — never persisted, never an eligibility check, never a
 * supported-device list or policy.
 */
class ShortcutKeyConsumptionTracker(
    private val submitMatchedDown: (
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        altPressed: Boolean,
        metaPressed: Boolean,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
    ) -> ShortcutCandidate?,
) {

    private data class StreamKey(val deviceId: Int, val keyCode: Int)

    private val active = mutableSetOf<StreamKey>()

    fun onKeyEvent(
        deviceId: Int,
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        altPressed: Boolean,
        metaPressed: Boolean,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
    ): ShortcutKeyDecision {
        val key = StreamKey(deviceId, keyCode)
        return when (action) {
            KeyEvent.ACTION_DOWN ->
                when {
                    // Ownership FIRST: any DOWN of an owned stream (a duplicate
                    // repeat==0 callback OR an auto-repeat) is consumed and never
                    // resubmits. Only unowned DOWNs are considered for matching.
                    active.contains(key) -> ShortcutKeyDecision(null, consume = true)
                    // Unowned initial press: match here (submits iff matched).
                    // Ownership/consumption follow the match, not the outcome.
                    repeatCount == 0 -> {
                        // Positional by necessity (a Kotlin function TYPE has no
                        // named arguments). Order must match submitMatchedDown's
                        // declaration above: alt, meta, ctrl, shift.
                        val candidate = submitMatchedDown(
                            action, keyCode, repeatCount,
                            altPressed, metaPressed, ctrlPressed, shiftPressed,
                        )
                        if (candidate != null) {
                            active.add(key)
                            ShortcutKeyDecision(candidate, consume = true)
                        } else {
                            ShortcutKeyDecision(null, consume = false)
                        }
                    }
                    // Unowned auto-repeat (its initial DOWN was not ours): pass through.
                    else -> ShortcutKeyDecision(null, consume = false)
                }
            KeyEvent.ACTION_UP ->
                // Release ownership; consume iff we owned it. No modifier rematch.
                ShortcutKeyDecision(null, consume = active.remove(key))
            else ->
                ShortcutKeyDecision(null, consume = false)
        }
    }

    /** Forgets all ownership on a service lifecycle reset. Synthesizes no events. */
    fun reset() {
        active.clear()
    }
}

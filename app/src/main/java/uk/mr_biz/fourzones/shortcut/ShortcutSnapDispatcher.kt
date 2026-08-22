package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.geometry.Quadrant

/**
 * Pure glue between a raw key event and one snap request: match → map → submit.
 * Extracted from the AccessibilityService so the "which events trigger a snap"
 * decision is unit-testable without Android plumbing.
 *
 * It requests a snap ONLY when [QuadrantShortcutMatcher] matches (initial
 * Alt+Meta+1..4 key-down, with Ctrl and Shift absent); it never reacts to
 * ACTION_UP, auto-repeat, or unmatched keys. It returns the matched candidate
 * (or null) so the caller can log the match diagnostic; it never consumes the
 * event.
 */
class ShortcutSnapDispatcher(private val requestSnap: (Quadrant) -> Unit) {

    fun handle(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        altPressed: Boolean,
        metaPressed: Boolean,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
    ): ShortcutCandidate? {
        val candidate = QuadrantShortcutMatcher.match(
            action = action,
            keyCode = keyCode,
            repeatCount = repeatCount,
            altPressed = altPressed,
            metaPressed = metaPressed,
            ctrlPressed = ctrlPressed,
            shiftPressed = shiftPressed,
        ) ?: return null
        requestSnap(QuadrantShortcutMapping.toQuadrant(candidate))
        return candidate
    }
}

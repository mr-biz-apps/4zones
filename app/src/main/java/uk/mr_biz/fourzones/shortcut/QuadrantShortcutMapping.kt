package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.geometry.Quadrant

/**
 * The one explicit, pure mapping from a matched [ShortcutCandidate] to the
 * [Quadrant] the existing snap engine understands. It encodes NO coordinates and
 * knows NOTHING about display geometry — that stays entirely inside the
 * validated Phase 2 geometry/orchestrator path. Trivially unit-testable.
 */
object QuadrantShortcutMapping {
    fun toQuadrant(candidate: ShortcutCandidate): Quadrant = when (candidate) {
        ShortcutCandidate.ONE -> Quadrant.TOP_LEFT
        ShortcutCandidate.TWO -> Quadrant.TOP_RIGHT
        ShortcutCandidate.THREE -> Quadrant.BOTTOM_LEFT
        ShortcutCandidate.FOUR -> Quadrant.BOTTOM_RIGHT
    }
}

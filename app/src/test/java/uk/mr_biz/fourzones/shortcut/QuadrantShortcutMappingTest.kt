package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.geometry.Quadrant
import org.junit.Assert.assertEquals
import org.junit.Test

class QuadrantShortcutMappingTest {

    @Test
    fun `candidates map to the four quadrants`() {
        assertEquals(Quadrant.TOP_LEFT, QuadrantShortcutMapping.toQuadrant(ShortcutCandidate.ONE))
        assertEquals(Quadrant.TOP_RIGHT, QuadrantShortcutMapping.toQuadrant(ShortcutCandidate.TWO))
        assertEquals(Quadrant.BOTTOM_LEFT, QuadrantShortcutMapping.toQuadrant(ShortcutCandidate.THREE))
        assertEquals(Quadrant.BOTTOM_RIGHT, QuadrantShortcutMapping.toQuadrant(ShortcutCandidate.FOUR))
    }
}

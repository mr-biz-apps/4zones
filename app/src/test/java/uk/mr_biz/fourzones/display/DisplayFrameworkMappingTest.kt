package uk.mr_biz.fourzones.display

import android.view.Display
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Framework constants used here (Surface.ROTATION_*, Display.FLAG_*) are
 * compile-time constants, so these mappings run as plain JVM unit tests.
 */
class DisplayFrameworkMappingTest {

    @Test
    fun `surface rotations map to degrees`() {
        assertEquals(DisplayRotation.DEG_0, rotationFromSurfaceRotation(Surface.ROTATION_0))
        assertEquals(DisplayRotation.DEG_90, rotationFromSurfaceRotation(Surface.ROTATION_90))
        assertEquals(DisplayRotation.DEG_180, rotationFromSurfaceRotation(Surface.ROTATION_180))
        assertEquals(DisplayRotation.DEG_270, rotationFromSurfaceRotation(Surface.ROTATION_270))
    }

    @Test
    fun `unknown rotation falls back to 0 degrees`() {
        assertEquals(DisplayRotation.DEG_0, rotationFromSurfaceRotation(42))
    }

    @Test
    fun `flag mask maps to model flags`() {
        assertEquals(emptySet<DisplayFlag>(), displayFlagsFromMask(0))
        assertEquals(
            setOf(DisplayFlag.PRESENTATION),
            displayFlagsFromMask(Display.FLAG_PRESENTATION),
        )
        assertEquals(
            setOf(DisplayFlag.PRESENTATION, DisplayFlag.PRIVATE, DisplayFlag.SECURE),
            displayFlagsFromMask(
                Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE or Display.FLAG_SECURE,
            ),
        )
        assertEquals(
            setOf(DisplayFlag.ROUND, DisplayFlag.SUPPORTS_PROTECTED_BUFFERS),
            displayFlagsFromMask(
                Display.FLAG_ROUND or Display.FLAG_SUPPORTS_PROTECTED_BUFFERS,
            ),
        )
    }

    @Test
    fun `unknown flag bits are ignored`() {
        assertEquals(
            setOf(DisplayFlag.PRESENTATION),
            displayFlagsFromMask(Display.FLAG_PRESENTATION or 0x40000000),
        )
    }
}

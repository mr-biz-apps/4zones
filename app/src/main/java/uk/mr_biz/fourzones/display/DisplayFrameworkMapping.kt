package uk.mr_biz.fourzones.display

import android.view.Display
import android.view.Surface

/**
 * Pure mapping from Android framework integer constants to the internal model.
 * Only compile-time constants from the framework are referenced, so these
 * functions remain unit-testable on the JVM.
 */

fun rotationFromSurfaceRotation(surfaceRotation: Int): DisplayRotation = when (surfaceRotation) {
    Surface.ROTATION_90 -> DisplayRotation.DEG_90
    Surface.ROTATION_180 -> DisplayRotation.DEG_180
    Surface.ROTATION_270 -> DisplayRotation.DEG_270
    else -> DisplayRotation.DEG_0
}

fun displayFlagsFromMask(mask: Int): Set<DisplayFlag> = buildSet {
    if (mask and Display.FLAG_PRESENTATION != 0) add(DisplayFlag.PRESENTATION)
    if (mask and Display.FLAG_PRIVATE != 0) add(DisplayFlag.PRIVATE)
    if (mask and Display.FLAG_SECURE != 0) add(DisplayFlag.SECURE)
    if (mask and Display.FLAG_ROUND != 0) add(DisplayFlag.ROUND)
    if (mask and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) {
        add(DisplayFlag.SUPPORTS_PROTECTED_BUFFERS)
    }
}

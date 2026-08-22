package uk.mr_biz.fourzones.workspace

import android.content.res.Configuration

/**
 * Pure mapping from Android framework integer constants to the internal model.
 * Only compile-time constants from the framework are referenced, so these
 * functions remain unit-testable on the JVM.
 */

/**
 * Maps a UI mode type value (UiModeManager.currentModeType, or an already
 * masked Configuration.uiMode) to [UiModeType]. Unrecognized values map to
 * [UiModeType.UNKNOWN] rather than being coerced to a known mode.
 */
fun uiModeTypeFromFrameworkType(type: Int): UiModeType = when (type) {
    Configuration.UI_MODE_TYPE_NORMAL -> UiModeType.NORMAL
    Configuration.UI_MODE_TYPE_DESK -> UiModeType.DESK
    Configuration.UI_MODE_TYPE_CAR -> UiModeType.CAR
    Configuration.UI_MODE_TYPE_TELEVISION -> UiModeType.TELEVISION
    Configuration.UI_MODE_TYPE_APPLIANCE -> UiModeType.APPLIANCE
    Configuration.UI_MODE_TYPE_WATCH -> UiModeType.WATCH
    Configuration.UI_MODE_TYPE_VR_HEADSET -> UiModeType.VR_HEADSET
    Configuration.UI_MODE_TYPE_UNDEFINED -> UiModeType.UNDEFINED
    else -> UiModeType.UNKNOWN
}

/** Maps raw Configuration.uiMode bits (type + night bits) to [UiModeType]. */
fun uiModeTypeFromUiModeBits(uiMode: Int): UiModeType =
    uiModeTypeFromFrameworkType(uiMode and Configuration.UI_MODE_TYPE_MASK)

/** Maps Configuration.orientation to [ScreenOrientation]. */
fun orientationFromConfiguration(orientation: Int): ScreenOrientation = when (orientation) {
    Configuration.ORIENTATION_PORTRAIT -> ScreenOrientation.PORTRAIT
    Configuration.ORIENTATION_LANDSCAPE -> ScreenOrientation.LANDSCAPE
    else -> ScreenOrientation.UNDEFINED
}

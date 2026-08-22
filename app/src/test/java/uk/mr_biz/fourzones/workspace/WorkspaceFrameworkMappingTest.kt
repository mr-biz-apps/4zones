package uk.mr_biz.fourzones.workspace

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFrameworkMappingTest {

    @Test
    fun `maps every known ui mode type`() {
        assertEquals(UiModeType.NORMAL, uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_NORMAL))
        assertEquals(UiModeType.DESK, uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_DESK))
        assertEquals(UiModeType.CAR, uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_CAR))
        assertEquals(
            UiModeType.TELEVISION,
            uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_TELEVISION),
        )
        assertEquals(
            UiModeType.APPLIANCE,
            uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_APPLIANCE),
        )
        assertEquals(UiModeType.WATCH, uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_WATCH))
        assertEquals(
            UiModeType.VR_HEADSET,
            uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_VR_HEADSET),
        )
        assertEquals(
            UiModeType.UNDEFINED,
            uiModeTypeFromFrameworkType(Configuration.UI_MODE_TYPE_UNDEFINED),
        )
    }

    @Test
    fun `unrecognized ui mode type maps to UNKNOWN not to a guessed mode`() {
        assertEquals(UiModeType.UNKNOWN, uiModeTypeFromFrameworkType(0x0e))
        assertEquals(UiModeType.UNKNOWN, uiModeTypeFromFrameworkType(Int.MAX_VALUE))
    }

    @Test
    fun `ui mode bits are masked so night bits do not corrupt the type`() {
        val deskWithNight = Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_YES
        val normalWithNight = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO

        assertEquals(UiModeType.DESK, uiModeTypeFromUiModeBits(deskWithNight))
        assertEquals(UiModeType.NORMAL, uiModeTypeFromUiModeBits(normalWithNight))
    }

    @Test
    fun `maps orientation values`() {
        assertEquals(
            ScreenOrientation.PORTRAIT,
            orientationFromConfiguration(Configuration.ORIENTATION_PORTRAIT),
        )
        assertEquals(
            ScreenOrientation.LANDSCAPE,
            orientationFromConfiguration(Configuration.ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            ScreenOrientation.UNDEFINED,
            orientationFromConfiguration(Configuration.ORIENTATION_UNDEFINED),
        )
        assertEquals(ScreenOrientation.UNDEFINED, orientationFromConfiguration(99))
    }
}

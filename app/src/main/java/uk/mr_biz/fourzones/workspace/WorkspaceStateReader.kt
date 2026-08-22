package uk.mr_biz.fourzones.workspace

import android.app.Activity
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowMetrics

/**
 * Thin framework adapter: reads public windowing information for a running
 * [Activity] into an immutable [WorkspaceState] snapshot. All interpretation
 * happens in [WorkspaceInterpreter]; this class only acquires data.
 *
 * The Activity itself is the correct source here: window metrics, multi-window
 * state and the hosting display are per-window/per-Activity concepts, so an
 * application context would be wrong. The Activity reference is a call
 * parameter only and is never retained.
 */
class WorkspaceStateReader {

    fun read(activity: Activity): WorkspaceState {
        // Activity is a UI context, so its WindowManager reports the metrics
        // of this Activity's own window — the whole point of this milestone.
        val windowManager = activity.windowManager
        val currentMetrics: WindowMetrics = windowManager.currentWindowMetrics
        val maximumMetrics: WindowMetrics = windowManager.maximumWindowMetrics
        val insets = currentMetrics.windowInsets
        val configuration = activity.resources.configuration

        return WorkspaceState(
            hostingDisplayId = activity.display?.displayId,
            isInMultiWindowMode = activity.isInMultiWindowMode,
            isInPictureInPictureMode = activity.isInPictureInPictureMode,
            supportsFreeformWindowManagement = activity.packageManager.hasSystemFeature(
                PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT,
            ),
            uiModeType = readUiModeType(activity),
            currentBounds = currentMetrics.bounds.toSnapshot(),
            maximumBounds = maximumMetrics.bounds.toSnapshot(),
            insets = WorkspaceInsets(
                statusBars = insets.valuesOf(WindowInsets.Type.statusBars()),
                navigationBars = insets.valuesOf(WindowInsets.Type.navigationBars()),
                systemBars = insets.valuesOf(WindowInsets.Type.systemBars()),
                displayCutout = insets.valuesOf(WindowInsets.Type.displayCutout()),
            ),
            configuration = ConfigurationSnapshot(
                screenWidthDp = configuration.screenWidthDp,
                screenHeightDp = configuration.screenHeightDp,
                smallestScreenWidthDp = configuration.smallestScreenWidthDp,
                orientation = orientationFromConfiguration(configuration.orientation),
            ),
        )
    }

    // UiModeManager is the primary source per-milestone spec; the Activity's
    // Configuration bits are an equivalent public fallback if it is missing.
    private fun readUiModeType(activity: Activity): UiModeType {
        val uiModeManager = activity.getSystemService(UiModeManager::class.java)
        return if (uiModeManager != null) {
            uiModeTypeFromFrameworkType(uiModeManager.currentModeType)
        } else {
            uiModeTypeFromUiModeBits(activity.resources.configuration.uiMode)
        }
    }

    private fun Rect.toSnapshot() = BoundsSnapshot(left, top, right, bottom)

    private fun WindowInsets.valuesOf(typeMask: Int): InsetValues {
        val insets = getInsets(typeMask)
        return InsetValues(insets.left, insets.top, insets.right, insets.bottom)
    }
}

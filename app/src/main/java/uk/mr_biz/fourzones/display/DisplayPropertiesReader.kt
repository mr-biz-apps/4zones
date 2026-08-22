package uk.mr_biz.fourzones.display

import android.content.Context
import android.os.Build
import android.view.Display

/**
 * Thin framework adapter: reads public information from a [Display] into an
 * immutable [DisplayProperties] snapshot. All interpretation happens in
 * [DisplayClassifier]; this class only acquires data.
 *
 * Dimensions come from the active display mode (physical panel pixels).
 * WindowManager is deliberately NOT used here: a display context from
 * createDisplayContext() is not a UI context and must not be used to obtain
 * WindowManager. Window/application bounds are a different concept and are
 * left to the geometry milestone, where a proper window context will exist.
 * The display context is used only for display-associated resources (density),
 * which is a valid non-UI use.
 */
class DisplayPropertiesReader(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @param presentationCategoryIds IDs currently returned by DisplayManager
     * for DISPLAY_CATEGORY_PRESENTATION. Used only to correlate that category
     * listing as diagnostic evidence — never as display identity.
     */
    fun read(display: Display, presentationCategoryIds: Set<Int>): DisplayProperties {
        val mode = display.mode
        val densityDpi = appContext.createDisplayContext(display)
            .resources.displayMetrics.densityDpi

        return DisplayProperties(
            displayId = display.displayId,
            name = display.name ?: "",
            physicalWidthPx = mode.physicalWidth,
            physicalHeightPx = mode.physicalHeight,
            rotation = rotationFromSurfaceRotation(display.rotation),
            refreshRateHz = display.refreshRate,
            densityDpi = densityDpi,
            flags = displayFlagsFromMask(display.flags),
            isDefaultDisplay = display.displayId == Display.DEFAULT_DISPLAY,
            inPresentationCategory = display.displayId in presentationCategoryIds,
            isInternal = if (supportsDisplayIsInternal()) display.isInternal else null,
        )
    }

    // The outer SDK_INT check keeps the SDK_INT_FULL field access off pre-36
    // devices, where that field does not exist.
    private fun supportsDisplayIsInternal(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
}

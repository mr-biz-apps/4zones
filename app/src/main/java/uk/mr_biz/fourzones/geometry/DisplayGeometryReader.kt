package uk.mr_biz.fourzones.geometry

import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Thin Android adapter: public APIs only, no UI code, no privileged calls.
 *
 *   Display (from Phase 1 discovery, looked up by its reported ID)
 *     -> display-specific window context (createWindowContext, API 31+)
 *     -> that context's WindowManager.maximumWindowMetrics
 *     -> typed bounds/insets model
 *     -> pure WorkspaceGeometryCalculator
 *
 * This reader acquires per-display maximum window bounds,
 * visibility-independent inset evidence, and the display's logical density
 * from public Android APIs. These readings feed the snap-workspace
 * calculation, whose exclusion rule has been hardware-correlated against
 * Samsung native half-snap task outer bounds on the tested DeX
 * configurations (A9 standalone, S25 external monitor) — a validated
 * policy for those configurations, not universally authoritative Samsung
 * geometry. maximumWindowMetrics is used (rather than any current window)
 * so the evidence is per-display and independent of whatever size DexZones
 * happens to be; the Activity's own freeform bounds are never consulted.
 * Density comes from the same display-specific window context's resources —
 * never inferred from resolution or model.
 *
 * Inset components are acquired INDIVIDUALLY (status, navigation, caption,
 * system overlays where the API exists, cutout) because the platform's
 * combined systemBars value includes the caption bar: that raw value is
 * retained as diagnostic evidence only and is never the applied mask.
 * Caption evidence: on the tested DeX configurations the task-local caption
 * inset was observed inside the snapped task outer rectangle, so DexZones
 * keeps it separate from the display-level mask (hardware-correlated for
 * those configurations, not a claimed universal rule). systemOverlays() is
 * API 34+; earlier platforms record zero rather than inferring overlays by
 * subtraction — inset masks are not safely algebraically subtractable.
 *
 * Insets are read with getInsetsIgnoringVisibility so a temporarily hidden
 * bar does not enlarge the candidate workspace, and the IME type is never
 * requested so a software keyboard can never shrink it.
 *
 * Every display is read independently; nothing is shared or reused across
 * displays. Acquisition failures become Unsupported (class name only) —
 * never a crash, never manufactured geometry.
 */
class DisplayGeometryReader(context: Context) {

    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    fun read(displayId: Int): DesktopWorkAreaAssessment {
        val display: Display = displayManager?.getDisplay(displayId)
            ?: return DesktopWorkAreaAssessment.Unsupported(
                displayId,
                "display is no longer attached",
            )
        return try {
            val windowContext = appContext.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                null,
            )
            val windowManager = windowContext.getSystemService(WindowManager::class.java)
                ?: return DesktopWorkAreaAssessment.Unsupported(
                    displayId,
                    "WindowManager unavailable for display context",
                )
            val metrics = windowManager.maximumWindowMetrics
            val insets = metrics.windowInsets
            val displayMetrics = windowContext.resources.displayMetrics
            WorkspaceGeometryCalculator.calculate(
                DisplayGeometryReading(
                    displayId = displayId,
                    maximumBounds = metrics.bounds.toGeometryRect(),
                    statusBarInsets = insets.stableInsets(WindowInsets.Type.statusBars()),
                    navigationBarInsets = insets.stableInsets(WindowInsets.Type.navigationBars()),
                    captionBarInsets = insets.stableInsets(WindowInsets.Type.captionBar()),
                    systemBarInsets = insets.stableInsets(WindowInsets.Type.systemBars()),
                    systemOverlayInsets =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            insets.stableInsets(WindowInsets.Type.systemOverlays())
                        } else {
                            GeometryInsets.NONE
                        },
                    displayCutoutInsets = insets.stableInsets(WindowInsets.Type.displayCutout()),
                    densityScale = displayMetrics.density,
                    densityDpi = displayMetrics.densityDpi,
                ),
            )
        } catch (e: Exception) {
            DesktopWorkAreaAssessment.Unsupported(
                displayId,
                "geometry unavailable (${e.javaClass.simpleName})",
            )
        }
    }

    private fun WindowInsets.stableInsets(typeMask: Int): GeometryInsets =
        getInsetsIgnoringVisibility(typeMask).toGeometryInsets()

    private fun Rect.toGeometryRect() = GeometryRect(left, top, right, bottom)

    private fun Insets.toGeometryInsets() = GeometryInsets(left, top, right, bottom)
}

package uk.mr_biz.fourzones.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Read-only helpers for the shortcut AccessibilityService's enablement, for the
 * diagnostic UI. This NEVER enables the service programmatically — it only
 * reports whether the user has enabled it and produces an Intent to open the
 * system Accessibility settings so the user can toggle it themselves.
 */
object ShortcutServiceStatus {

    /** True when the user has enabled DexZones' shortcut service in settings. */
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, QuadrantShortcutAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** Intent to open system Accessibility settings (user enables it manually). */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

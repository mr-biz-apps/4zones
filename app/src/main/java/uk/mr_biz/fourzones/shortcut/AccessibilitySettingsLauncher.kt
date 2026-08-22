package uk.mr_biz.fourzones.shortcut

/**
 * D-1-CORR (H7d) — the injectable containment boundary for the ONE settings deep-link that the
 * accessibility prominent-disclosure gate authorizes.
 *
 * Before this boundary existed, `MainActivity` called `startActivity(accessibilitySettingsIntent())`
 * bare. On a device with no Activity for `Settings.ACTION_ACCESSIBILITY_SETTINGS` (or where the launch
 * is denied) that call threw AFTER affirmative consent had been persisted and AFTER the disclosure
 * dialog had been dismissed: the user had consented, the dialog was gone, and the app crashed.
 *
 * The ordered behaviour is:
 *  - the bounded, recoverable domain does NOT crash the app;
 *  - consent REMAINS granted — the user did consent, and the launch outcome is a separate transient
 *    fact that must not rewrite the record of their decision;
 *  - the caller receives the defined recovery state so it can show the manual route;
 *  - nothing latches — a later attempt calls [launch] again.
 *
 * CAUGHT DOMAIN. [RuntimeException] only. That is the tightest single type that contains BOTH
 * ordered members of the recoverable domain — `android.content.ActivityNotFoundException` and
 * `java.lang.SecurityException` are both [RuntimeException]s — while staying strictly inside the
 * "non-fatal Exception" bound. Fatal [Error]s (VM, linkage, OOM) are NOT caught and propagate
 * unchanged; a `catch (Throwable)` here would swallow them and is prohibited. This is the same
 * containment shape already shipping in `uk.mr_biz.fourzones.capture.CaptureStartBoundary`.
 *
 * SANITIZATION. The boundary is total: no exception object, message, cause or stack trace crosses it.
 * Every failure — whatever its type or message — collapses to the single value
 * [AccessibilitySettingsLaunchResult.UNAVAILABLE], so no device- or app-specific text can reach the UI
 * through this path.
 *
 * Framework-free by construction: the actual `startActivity` call is supplied by the caller as
 * [launch], so the failure path is host-JVM-testable without instantiating an Activity.
 */
object AccessibilitySettingsLauncher {

    /**
     * Runs [launch] exactly once and reports the outcome. Never retries internally: one call here is
     * one launch attempt, so one disclosure acceptance can never become two settings launches.
     */
    fun open(launch: () -> Unit): AccessibilitySettingsLaunchResult =
        try {
            launch()
            AccessibilitySettingsLaunchResult.OPENED
        } catch (recoverable: RuntimeException) {
            // Sanitized on purpose: `recoverable` is deliberately not read, logged or forwarded.
            AccessibilitySettingsLaunchResult.UNAVAILABLE
        }
}

/** The sanitized outcome of one attempt to open system Accessibility settings. */
enum class AccessibilitySettingsLaunchResult {
    /** System Accessibility settings were launched. No recovery state. */
    OPENED,

    /**
     * The launch failed inside the recoverable domain. THIS is the defined recovery state: the caller
     * must surface the manual Settings -> Accessibility route, must leave consent granted, and must
     * leave every enable entry point usable.
     */
    UNAVAILABLE,
}

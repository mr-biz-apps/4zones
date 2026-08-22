package uk.mr_biz.fourzones.ui

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus

/**
 * Pure, framework-free label strings for the developer diagnostics screen, so
 * the accuracy-sensitive wording (screen heading, section titles, and the two
 * DISTINCT backend statuses) is unit-testable without Compose.
 *
 * The two backends are genuinely independent lifecycles and are shown as
 * separate lines here so a developer can never confuse them:
 *  - [activityBackendLabel] — MainActivity's foreground backend
 *    (permission / diagnostics / manual tooling), from `topologyBackendStatus`.
 *  - [shortcutBackendLabel] — the AccessibilityService-owned global-shortcut
 *    backend, from `ShortcutBackendStatusStore` via MainActivity's
 *    `shortcutBackendStatus`. This is the ONLY backend that feeds product
 *    readiness; a `null` value means "no current observation", never READY.
 *
 * These expose the RAW status (enum name), not the product/ProductReadiness
 * mapping — diagnostics show the truth; the product screen owns semantics.
 */
object DiagnosticsLabels {

    const val SCREEN_HEADING = "Developer diagnostics"
    const val SCREEN_SUBTEXT =
        "Detailed DeX, topology, shortcut and snap state for testing and support."

    /** Accurate replacement for the stale "Global shortcuts (reception only)". */
    const val SECTION_SHORTCUTS = "Global keyboard shortcuts"

    const val ACTIVITY_BACKEND_CONTEXT =
        "Activity backend — Shizuku shell identity (foreground: permission / diagnostics / manual tooling)"
    const val SHORTCUT_BACKEND_CONTEXT =
        "Shortcut backend — AccessibilityService-owned global shortcut path"

    fun activityBackendLabel(status: PrivilegedBackendStatus?): String =
        "Activity backend: ${status?.name ?: "not started"}"

    fun shortcutBackendLabel(status: PrivilegedBackendStatus?): String =
        "Shortcut backend: ${status?.name ?: "no current observation"}"
}

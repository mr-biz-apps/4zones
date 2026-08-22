package uk.mr_biz.fourzones.product

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus

/**
 * User-action-distinct product readiness for the 4Zones main screen. Each
 * state exists because its recovery action differs; wording/actions are mapped
 * in the UI layer (4B3), never here.
 *
 * [READY] means ONLY that the keyboard-shortcut infrastructure is ready (the
 * shortcut-owned backend has a current, protocol-verified privileged service).
 * It does NOT mean a valid snap target exists, DeX is active, an external
 * display or logical desktop exists, geometry will succeed, or that the next
 * shortcut will verify — target availability stays transactional at shortcut
 * press time and is deliberately NOT an input to this model.
 */
enum class ProductReadiness {
    SHORTCUT_SERVICE_DISABLED,
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_UNAVAILABLE,
    SHIZUKU_PERMISSION_REQUIRED,
    UNSUPPORTED_SHIZUKU,
    RESTART_REQUIRED,
    CONNECTING,
    READY,
}

/**
 * Pure, fail-conservative readiness mapping.
 *
 * [serviceEnabled] is the refreshed Accessibility enablement
 * (`ShortcutServiceStatus.isEnabled`) and dominates EVERY backend value,
 * including READY. [backendStatus] is the shortcut-owned backend's published
 * status (`ShortcutBackendStatusStore.latest()`) — never MainActivity's
 * backend; `null` (no current observation) maps to [ProductReadiness.CONNECTING],
 * never READY.
 *
 * The `when` is deliberately exhaustive with no `else`: adding a
 * [PrivilegedBackendStatus] value fails compilation here, so a future status
 * can never silently map to READY.
 */
fun productReadiness(
    serviceEnabled: Boolean,
    backendStatus: PrivilegedBackendStatus?,
): ProductReadiness {
    if (!serviceEnabled) return ProductReadiness.SHORTCUT_SERVICE_DISABLED
    return when (backendStatus) {
        null -> ProductReadiness.CONNECTING
        PrivilegedBackendStatus.NOT_INSTALLED -> ProductReadiness.SHIZUKU_NOT_INSTALLED
        // Collapsed ONLY because the recovery action is identical: start Shizuku.
        PrivilegedBackendStatus.BINDER_UNAVAILABLE,
        PrivilegedBackendStatus.BINDER_DIED,
        -> ProductReadiness.SHIZUKU_UNAVAILABLE
        // Collapsed ONLY because the recovery action is identical: allow 4Zones.
        PrivilegedBackendStatus.PERMISSION_REQUIRED,
        PrivilegedBackendStatus.PERMISSION_DENIED,
        -> ProductReadiness.SHIZUKU_PERMISSION_REQUIRED
        PrivilegedBackendStatus.UNSUPPORTED_SERVER -> ProductReadiness.UNSUPPORTED_SHIZUKU
        PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH -> ProductReadiness.RESTART_REQUIRED
        PrivilegedBackendStatus.CONNECTING -> ProductReadiness.CONNECTING
        PrivilegedBackendStatus.READY -> ProductReadiness.READY
    }
}

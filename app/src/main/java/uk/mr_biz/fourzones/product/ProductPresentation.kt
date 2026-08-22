package uk.mr_biz.fourzones.product

/**
 * Pure presentation data for the 4Zones product screen. This maps the ALREADY
 * DERIVED [ProductReadiness] to wording and action kinds only — it never
 * re-maps backend statuses ([productReadiness] stays the sole semantic
 * readiness mapper), exposes no raw engineering state, and holds no mutable
 * state. The UI binds each [SetupActionKind] to its actual handler.
 */
enum class SetupActionKind {
    /** Open the system Accessibility settings (user enables the service). */
    OPEN_ACCESSIBILITY_SETTINGS,

    /** Request the Shizuku permission via MainActivity's foreground backend. */
    REQUEST_SHIZUKU_PERMISSION,
}

/** One setup/recovery card; [actionKind]/[actionLabel] are both set or both null. */
data class ProductSetupContent(
    val headline: String,
    val body: String,
    val actionKind: SetupActionKind?,
    val actionLabel: String?,
)

/**
 * Banner headline. READY deliberately reads "Keyboard shortcuts ready" (never
 * "Ready to snap"): it asserts infrastructure readiness only, not that a
 * valid target/DeX workspace currently exists.
 */
fun productBanner(readiness: ProductReadiness): String = when (readiness) {
    ProductReadiness.READY -> "Keyboard shortcuts ready"
    ProductReadiness.CONNECTING -> "Starting…"
    ProductReadiness.SHORTCUT_SERVICE_DISABLED,
    ProductReadiness.SHIZUKU_NOT_INSTALLED,
    ProductReadiness.SHIZUKU_UNAVAILABLE,
    ProductReadiness.SHIZUKU_PERMISSION_REQUIRED,
    ProductReadiness.UNSUPPORTED_SHIZUKU,
    ProductReadiness.RESTART_REQUIRED,
    -> "Setup required"
}

/**
 * The setup/recovery card for the current readiness, or `null` when none
 * applies (READY needs nothing; CONNECTING is brief and self-resolving, so no
 * button is offered). Guidance stays truthful: 4Zones can neither install nor
 * start Shizuku itself, and restarting Shizuku is recovery guidance, not a
 * diagnosis.
 */
fun productSetupContent(readiness: ProductReadiness): ProductSetupContent? = when (readiness) {
    ProductReadiness.READY,
    ProductReadiness.CONNECTING,
    -> null
    ProductReadiness.SHORTCUT_SERVICE_DISABLED -> ProductSetupContent(
        headline = "Keyboard shortcuts are off",
        body = "Android requires the 4Zones keyboard shortcut service to be enabled.",
        actionKind = SetupActionKind.OPEN_ACCESSIBILITY_SETTINGS,
        actionLabel = "Enable keyboard shortcuts",
    )
    ProductReadiness.SHIZUKU_NOT_INSTALLED -> ProductSetupContent(
        headline = "Shizuku is required",
        body = "4Zones uses Shizuku to resize DeX windows. Install Shizuku and " +
            "start its service — on Android 11+ this works over Wireless " +
            "debugging, no computer needed — then return to 4Zones.",
        actionKind = null,
        actionLabel = null,
    )
    ProductReadiness.SHIZUKU_UNAVAILABLE -> ProductSetupContent(
        headline = "Shizuku unavailable",
        body = "Start Shizuku, then return to 4Zones. 4Zones cannot start " +
            "Shizuku itself. After a phone restart, Shizuku must be started " +
            "again before shortcuts can resize windows.",
        actionKind = null,
        actionLabel = null,
    )
    ProductReadiness.SHIZUKU_PERMISSION_REQUIRED -> ProductSetupContent(
        headline = "Permission required",
        body = "4Zones needs Shizuku permission to resize DeX windows.",
        actionKind = SetupActionKind.REQUEST_SHIZUKU_PERMISSION,
        actionLabel = "Allow permission",
    )
    ProductReadiness.UNSUPPORTED_SHIZUKU -> ProductSetupContent(
        headline = "A supported Shizuku version must be running",
        body = "Update Shizuku if needed, then restart it.",
        actionKind = null,
        actionLabel = null,
    )
    ProductReadiness.RESTART_REQUIRED -> ProductSetupContent(
        headline = "Restart required",
        body = "The privileged service must be restarted before shortcuts can " +
            "become ready. Restarting Shizuku usually recovers this.",
        actionKind = null,
        actionLabel = null,
    )
}

/**
 * One row of the shortcut reference. Zone-first wording is UI-only: the
 * chord→zone association is the permanent concept, the position label is the
 * current (v1 quadrant) layout's description of that zone. The engine's
 * Quadrant/mapping types are untouched.
 */
data class ShortcutZoneReference(
    val digit: Int,
    val zoneLabel: String,
    val positionLabel: String,
    val chordLabel: String,
)

/** V1 four-zone reference, in zone order, matching the validated quadrants. */
val shortcutZoneReference: List<ShortcutZoneReference> = listOf(
    ShortcutZoneReference(1, "Zone 1", "Top left", "Alt + Win + 1"),
    ShortcutZoneReference(2, "Zone 2", "Top right", "Alt + Win + 2"),
    ShortcutZoneReference(3, "Zone 3", "Bottom left", "Alt + Win + 3"),
    ShortcutZoneReference(4, "Zone 4", "Bottom right", "Alt + Win + 4"),
)

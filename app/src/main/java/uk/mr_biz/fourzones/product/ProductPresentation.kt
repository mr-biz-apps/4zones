package uk.mr_biz.fourzones.product

import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.snap.SnapExecutionResult
import uk.mr_biz.fourzones.snap.SnapExecutionState

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
    // Split out of the "Setup required" group: with tap-to-snap the app WORKS
    // without the accessibility service, so this state is a missing OPTION, not
    // a blocked product.
    ProductReadiness.SHORTCUT_SERVICE_DISABLED -> "Keyboard shortcuts are off"
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
        // The card sits at the FOOT of the screen offering an alternative, so it
        // asks rather than announces — the banner above it already states that
        // the shortcuts are off, and repeating that here said it twice.
        headline = "Prefer the keyboard?",
        body = "Enable the 4Zones shortcut service to use Alt + Win + 1–4 " +
            "instead of tapping a zone.",
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
 * One row of the zone reference. Zone-first wording is UI-only: the chord→zone
 * association is the permanent concept, the position label is the current (v1
 * quadrant) layout's description of that zone. The engine's Quadrant/mapping
 * types are untouched.
 *
 * [quadrant] is the SINGLE source of the zone→[Quadrant] association the screen
 * uses when a card is tapped: there is no parallel digit→quadrant table in the
 * UI layer. It agrees with the keyboard path's
 * [uk.mr_biz.fourzones.shortcut.QuadrantShortcutMapping] by test, not by
 * coincidence.
 */
data class ShortcutZoneReference(
    val digit: Int,
    val zoneLabel: String,
    val positionLabel: String,
    val chordLabel: String,
    val quadrant: Quadrant,
) {
    /**
     * Accessibility label for the tappable card. Deliberately describes the
     * ACTION and the destination ("Snap window to top left") rather than the
     * card's visible heading ("Zone 1"), which tells a screen-reader user
     * nothing about what tapping does. Derived from [positionLabel] so the
     * spoken destination can never drift from the printed one.
     */
    val snapContentDescription: String
        get() = "Snap window to " + positionLabel.lowercase()
}

/** V1 four-zone reference, in zone order, matching the validated quadrants. */
val shortcutZoneReference: List<ShortcutZoneReference> = listOf(
    ShortcutZoneReference(1, "Zone 1", "Top left", "Alt + Win + 1", Quadrant.TOP_LEFT),
    ShortcutZoneReference(2, "Zone 2", "Top right", "Alt + Win + 2", Quadrant.TOP_RIGHT),
    ShortcutZoneReference(3, "Zone 3", "Bottom left", "Alt + Win + 3", Quadrant.BOTTOM_LEFT),
    ShortcutZoneReference(4, "Zone 4", "Bottom right", "Alt + Win + 4", Quadrant.BOTTOM_RIGHT),
)

/** Product-screen copy that is not readiness-derived, kept here with the rest of the wording. */
object ProductCopy {
    /**
     * The screen subtitle. It names what the product DOES (snap windows), not
     * how one asks for it — tapping and the keyboard chord are two routes to
     * the same thing, and only one of them needs the accessibility service.
     */
    const val SUBTITLE = "Snap windows to screen zones on Samsung DeX."

    /** Heading above the zone grid. The grid is now a control, not a key reference. */
    const val ZONES_HEADING = "Zones"

    /**
     * Carries the deliberate delay. Without it a tap reads as a fault: nothing
     * visibly happens for several seconds, because the target is captured when
     * the timer fires, not when the card is tapped.
     *
     * Deliberately order-AGNOSTIC, and it names no input device. Focusing the
     * window first and tapping second works exactly as well as the reverse —
     * the resolver excludes 4Zones itself and takes the last focused task
     * either way — and on a device with no external monitor, focus-first is the
     * ONLY order available. "Click the window" assumed both a mouse and a
     * second display.
     */
    const val TAP_INSTRUCTION =
        "Tap a zone. The window you're using moves a few seconds later — " +
            "bring it to the front if it isn't already."
}

/**
 * Whether the zone cards may be tapped to snap.
 *
 * The input is the Activity's OWN privileged backend status — deliberately NOT
 * [ProductReadiness]. Accessibility dominating every backend value in
 * [productReadiness] is a safety invariant of the KEYBOARD path; tapping a card
 * needs no accessibility service at all, so gating taps on that model would
 * disable a feature that demonstrably works with the service switched off.
 *
 * True for [PrivilegedBackendStatus.READY] and nothing else; `null` (no
 * observation yet) is false. The `when` is exhaustive with no `else`, so a
 * future status cannot silently enable a mutating control.
 */
fun snapControlsEnabled(status: PrivilegedBackendStatus?): Boolean = when (status) {
    null -> false
    PrivilegedBackendStatus.READY -> true
    PrivilegedBackendStatus.NOT_INSTALLED,
    PrivilegedBackendStatus.BINDER_UNAVAILABLE,
    PrivilegedBackendStatus.UNSUPPORTED_SERVER,
    PrivilegedBackendStatus.PERMISSION_REQUIRED,
    PrivilegedBackendStatus.PERMISSION_DENIED,
    PrivilegedBackendStatus.CONNECTING,
    PrivilegedBackendStatus.BINDER_DIED,
    PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH,
    -> false
}

/**
 * Why the zone cards are not tappable, or `null` when they are — so a disabled
 * card always states a reason instead of failing silently.
 *
 * It must stand on its own: when the accessibility service is off,
 * [productReadiness] reports SHORTCUT_SERVICE_DISABLED and the setup card above
 * says nothing about the privileged backend, so pointing at that card would be
 * misleading.
 *
 * ONE LINE PER RECOVERY ACTION, grouped exactly as [productReadiness] groups
 * the same statuses, so the two can never tell a user different things about
 * one device state — a test asserts the grouping, it is not maintained by
 * memory. Flattening all seven into a single sentence was wrong for the
 * commonest one: with Shizuku running but 4Zones not allowed in it
 * ([PrivilegedBackendStatus.PERMISSION_REQUIRED]) it said "Start Shizuku",
 * which the user had already done.
 *
 * The actions name Shizuku because Shizuku is the only privileged backend the
 * released app offers, and they deliberately do NOT mention the unreleased
 * direct-access path: instructing an action the build provides no way to take
 * is worse than saying less. When a second backend ships, this grouping is
 * where its recovery wording is added.
 */
fun snapDisabledReason(status: PrivilegedBackendStatus?): String? = when (status) {
    PrivilegedBackendStatus.READY -> null
    // Transient and self-resolving: say so rather than sending the user to setup.
    null,
    PrivilegedBackendStatus.CONNECTING,
    -> "Starting up — zones become tappable in a moment."
    PrivilegedBackendStatus.NOT_INSTALLED ->
        "Zones need window access. Install Shizuku, start its service, then come back."
    // Collapsed ONLY because the recovery action is identical: start Shizuku.
    PrivilegedBackendStatus.BINDER_UNAVAILABLE,
    PrivilegedBackendStatus.BINDER_DIED,
    -> "Zones need window access. Start Shizuku, then come back."
    // Collapsed ONLY because the recovery action is identical: allow 4Zones.
    PrivilegedBackendStatus.PERMISSION_REQUIRED,
    PrivilegedBackendStatus.PERMISSION_DENIED,
    -> "Zones need window access. Allow 4Zones in Shizuku, then come back."
    PrivilegedBackendStatus.UNSUPPORTED_SERVER ->
        "Zones need window access. Update Shizuku, then restart it."
    PrivilegedBackendStatus.USER_SERVICE_VERSION_MISMATCH ->
        "Zones need window access. Restart 4Zones."
}

/**
 * The one consumer-level line describing the current snap, or `null` for
 * nothing to say.
 *
 * Strictly product wording: it never surfaces a component or package name, a
 * task id, raw bounds, a backend status or an engine reason string. Those exist
 * and stay in the diagnostics screen, which is where an engineer looks.
 */
fun snapFeedbackLine(state: SnapExecutionState): String? = when (state) {
    SnapExecutionState.Idle -> null
    is SnapExecutionState.Pending ->
        "Snapping to ${zonePosition(state.quadrant)} shortly — " +
            "bring the window you want to the front."
    is SnapExecutionState.Executing -> "Moving the window…"
    is SnapExecutionState.Completed -> completedSnapFeedback(state.result)
}

/**
 * One short sentence per finished outcome. Every branch is a fixed sentence:
 * no engine [SnapExecutionResult] field is ever interpolated, which is what
 * keeps component names, task ids and bounds off the product screen by
 * construction rather than by review.
 */
private fun completedSnapFeedback(result: SnapExecutionResult): String? = when (result) {
    is SnapExecutionResult.AppliedAndVerified -> "Snapped to ${zonePosition(result.quadrant)}."
    is SnapExecutionResult.NoTarget -> "No window to move. Bring one to the front, then tap a zone."
    // Unreachable in the UI: the controller drops superseded/stopped results
    // instead of publishing them, and a replacement request publishes its own
    // Pending line immediately. Silence is correct if that ever changes.
    is SnapExecutionResult.Cancelled -> null
    is SnapExecutionResult.GeometryUnavailable -> "Couldn't measure this screen. Try again."
    is SnapExecutionResult.PreconditionChanged -> "The window changed before it moved. Try again."
    is SnapExecutionResult.PrivilegeUnavailable -> "Window access isn't available right now."
    is SnapExecutionResult.TopologyUnavailable -> "Couldn't read the open windows. Try again."
    is SnapExecutionResult.InvalidDestination -> "That zone isn't available on this screen."
    is SnapExecutionResult.CommandFailed -> "The window couldn't be moved. Try again."
    is SnapExecutionResult.CommandTimedOut -> "That took too long. Try again."
    is SnapExecutionResult.PostconditionUnavailable -> "Couldn't confirm the window moved."
    is SnapExecutionResult.PostconditionMismatch ->
        "The window didn't fit the zone exactly — the app may have resisted the size."
}

/** Spoken/printed position for a quadrant, read from the single zone reference. */
private fun zonePosition(quadrant: Quadrant): String =
    shortcutZoneReference.firstOrNull { it.quadrant == quadrant }
        ?.positionLabel
        ?.lowercase()
        ?: "the zone"

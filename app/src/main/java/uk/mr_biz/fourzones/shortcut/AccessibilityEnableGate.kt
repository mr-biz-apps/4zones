package uk.mr_biz.fourzones.shortcut

/**
 * Phase 4D — the pure disclosure gate. It encodes the compliance invariant so it is host-JVM-testable
 * and un-bypassable in wiring: an "enable" request NEVER goes straight to the system-settings
 * deep-link — it ALWAYS shows the prominent disclosure first; the deep-link happens ONLY after
 * affirmative consent; declining dismisses and leaves the service off (the rest of the app keeps
 * working).
 *
 * Framework-free (no Android types) so `MainActivity` merely maps [Action]s to Compose state + the
 * `startActivity(accessibilitySettingsIntent())` call.
 */
class AccessibilityEnableGate(private val consentStore: AccessibilityDisclosureConsentStore) {

    enum class Action {
        /** Show the prominent-disclosure dialog. The ONLY response to an enable request. */
        SHOW_DISCLOSURE,

        /** Affirmative consent recorded → proceed to the system-settings deep-link. */
        DEEP_LINK,

        /** Declined/dismissed → close the dialog; do not enable; app stays usable. */
        DISMISS,
    }

    /** A user tapped an "enable keyboard shortcuts" entry point. NEVER returns [Action.DEEP_LINK]. */
    fun onEnableRequested(): Action = Action.SHOW_DISCLOSURE

    /** The user affirmatively consented in the disclosure. Records consent, then deep-links. */
    fun onConsentAccepted(nowMillis: Long): Action {
        consentStore.recordConsent(nowMillis)
        return Action.DEEP_LINK
    }

    /** The user declined/dismissed the disclosure. No deep-link; service stays off. */
    fun onConsentDeclined(): Action = Action.DISMISS
}

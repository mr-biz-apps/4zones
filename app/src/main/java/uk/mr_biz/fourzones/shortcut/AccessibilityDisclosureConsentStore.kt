package uk.mr_biz.fourzones.shortcut

import android.content.Context

/**
 * Phase 4D — persistent record that the user was shown the accessibility prominent-disclosure and
 * affirmatively consented BEFORE the app deep-linked them to enable the service. Backed by platform
 * `SharedPreferences` (no new dependency).
 *
 * Distinct from [ShortcutBackendStatusStore] (which is process-local, non-persisted backend STATUS —
 * wrong lifetime and semantics for consent). This store's flag is an auditable record; it is NOT the
 * compliance mechanism — the disclosure gate ([AccessibilityEnableGate]) precedes EVERY enable
 * deep-link regardless, so compliance never depends on this value.
 *
 * Persistence is behind the tiny [ConsentPreferences] seam so the round-trip is host-JVM-testable
 * without Android `SharedPreferences`.
 */
class AccessibilityDisclosureConsentStore(private val prefs: ConsentPreferences) {

    /** True once affirmative consent has ever been recorded. */
    fun hasConsented(): Boolean = prefs.getLong(KEY_CONSENT_AT_MILLIS, 0L) > 0L

    /** The wall-clock time affirmative consent was recorded, or 0 if never. */
    fun consentGivenAtMillis(): Long = prefs.getLong(KEY_CONSENT_AT_MILLIS, 0L)

    /** Record affirmative consent (first write wins; re-consent refreshes the timestamp). */
    fun recordConsent(nowMillis: Long) {
        prefs.putLong(KEY_CONSENT_AT_MILLIS, nowMillis)
    }

    companion object {
        const val PREFS_NAME = "dexzones_accessibility_consent"
        const val KEY_CONSENT_AT_MILLIS = "accessibility_disclosure_consent_at_millis"

        /** Production factory over platform `SharedPreferences`. */
        fun forContext(context: Context): AccessibilityDisclosureConsentStore =
            AccessibilityDisclosureConsentStore(SharedPrefsConsentPreferences(context.applicationContext))
    }
}

/** Minimal persistence seam (host-testable with a fake; production uses `SharedPreferences`). */
interface ConsentPreferences {
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

/** Production [ConsentPreferences] over platform `SharedPreferences`. */
class SharedPrefsConsentPreferences(context: Context) : ConsentPreferences {
    private val prefs = context.getSharedPreferences(
        AccessibilityDisclosureConsentStore.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}

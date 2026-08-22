package uk.mr_biz.fourzones.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4D — host-JVM persistence round-trip for the consent store (via a fake preferences seam). */
class AccessibilityDisclosureConsentStoreTest {

    private class FakeConsentPreferences : ConsentPreferences {
        val map = HashMap<String, Long>()
        override fun getLong(key: String, default: Long): Long = map[key] ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
    }

    @Test
    fun startsWithNoConsent() {
        val store = AccessibilityDisclosureConsentStore(FakeConsentPreferences())
        assertFalse(store.hasConsented())
        assertEquals(0L, store.consentGivenAtMillis())
    }

    @Test
    fun recordConsentPersistsTimestamp() {
        val store = AccessibilityDisclosureConsentStore(FakeConsentPreferences())
        store.recordConsent(1_725_000_000_000L)
        assertTrue(store.hasConsented())
        assertEquals(1_725_000_000_000L, store.consentGivenAtMillis())
    }

    @Test
    fun consentSurvivesAcrossStoreInstancesOnTheSameBackingPrefs() {
        val prefs = FakeConsentPreferences()
        AccessibilityDisclosureConsentStore(prefs).recordConsent(42L)
        // A FRESH store over the same backing store must read the persisted value (not in-memory).
        val reopened = AccessibilityDisclosureConsentStore(prefs)
        assertTrue(reopened.hasConsented())
        assertEquals(42L, reopened.consentGivenAtMillis())
    }
}

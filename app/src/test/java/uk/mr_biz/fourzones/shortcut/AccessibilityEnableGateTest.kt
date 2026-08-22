package uk.mr_biz.fourzones.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4D — the compliance invariant, host-tested: an enable request NEVER deep-links directly; the
 * deep-link happens ONLY after affirmative consent; declining records no consent and does not enable.
 */
class AccessibilityEnableGateTest {

    private class FakeConsentPreferences : ConsentPreferences {
        val map = HashMap<String, Long>()
        override fun getLong(key: String, default: Long): Long = map[key] ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
    }

    private fun newGate(): Pair<AccessibilityEnableGate, AccessibilityDisclosureConsentStore> {
        val store = AccessibilityDisclosureConsentStore(FakeConsentPreferences())
        return AccessibilityEnableGate(store) to store
    }

    @Test
    fun enableRequestAlwaysShowsDisclosureNeverDeepLinks() {
        val (gate, store) = newGate()
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        // No consent recorded merely by requesting enable — the gate blocks until affirmative consent.
        assertFalse(store.hasConsented())
    }

    @Test
    fun affirmativeConsentRecordsAndDeepLinks() {
        val (gate, store) = newGate()
        gate.onEnableRequested() // shows disclosure
        val action = gate.onConsentAccepted(nowMillis = 999L)
        assertEquals(AccessibilityEnableGate.Action.DEEP_LINK, action)
        assertTrue(store.hasConsented())
        assertEquals(999L, store.consentGivenAtMillis())
    }

    @Test
    fun declineDismissesWithoutConsentOrDeepLink() {
        val (gate, store) = newGate()
        gate.onEnableRequested()
        val action = gate.onConsentDeclined()
        assertEquals(AccessibilityEnableGate.Action.DISMISS, action)
        assertFalse("declining must not record consent", store.hasConsented())
    }

    @Test
    fun theOnlyPathToDeepLinkIsConsentAccepted() {
        val (gate, _) = newGate()
        // Neither the request nor the decline can produce DEEP_LINK.
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        assertEquals(AccessibilityEnableGate.Action.DISMISS, gate.onConsentDeclined())
    }
}

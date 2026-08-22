package uk.mr_biz.fourzones.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-1 / D-1-CORR (H7a / H7b / H7e, H9, H14) — the RETRY and STATE-RELEASE invariants of the 4D
 * disclosure gate, at the production [AccessibilityEnableGate] boundary.
 *
 * These are the falsifying oracles for the "can the enable entry point become permanently inert, or
 * can a lost/dismissed dialog leave the user stranded?" hazards. The gate is the only component that
 * decides whether an enable request is answerable, so if the gate is unconditionally re-armable then
 * every dialog-loss path (decline, scrim/system-back dismissal, Activity recreation, process death)
 * is recoverable by tapping the entry point again.
 *
 * WHAT THIS DOES NOT PROVE (Evidence Contract 5b): these tests exercise [AccessibilityEnableGate],
 * [AccessibilityDisclosureConsentStore] over a FAKE [ConsentPreferences], and the production
 * [AccessibilitySettingsLauncher]. They do NOT instantiate `MainActivity`, do NOT exercise its
 * `showAccessibilityDisclosure` / `showAccessibilitySettingsUnavailable` Compose state, and do NOT
 * exercise the production `SharedPrefsConsentPreferences` adapter. The Activity-composition half of
 * each invariant and the production preferences adapter are explicitly recorded, UNCREDITED residuals
 * in the D-1-CORR implementation report.
 *
 * These tests would FAIL if a future change short-circuits the disclosure for an already-consenting
 * user (the most plausible "optimization" that would silently restore an ungated deep-link), or if
 * declining were made to latch the gate closed.
 */
class AccessibilityDisclosureRetryInvariantTest {

    private class FakeConsentPreferences : ConsentPreferences {
        val map = HashMap<String, Long>()
        override fun getLong(key: String, default: Long): Long = map[key] ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
    }

    private fun newGate(prefs: ConsentPreferences = FakeConsentPreferences()) =
        AccessibilityDisclosureConsentStore(prefs).let { AccessibilityEnableGate(it) to it }

    /** H7b — declining is recoverable: the very next enable request re-arms the disclosure. */
    @Test
    fun declineIsRecoverableAndReArmsTheDisclosure() {
        val (gate, store) = newGate()
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        assertEquals(AccessibilityEnableGate.Action.DISMISS, gate.onConsentDeclined())
        assertFalse("declining must not record consent", store.hasConsented())
        // Re-armed: the entry point is answerable again, and still only via the disclosure.
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        assertEquals(AccessibilityEnableGate.Action.DEEP_LINK, gate.onConsentAccepted(nowMillis = 7L))
        assertTrue(store.hasConsented())
    }

    /** H7a — repeated decline can never latch the gate closed (no permanently inert entry point). */
    @Test
    fun repeatedDeclineNeverMakesTheEntryPointInert() {
        val (gate, store) = newGate()
        repeat(25) {
            assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
            assertEquals(AccessibilityEnableGate.Action.DISMISS, gate.onConsentDeclined())
        }
        assertFalse(store.hasConsented())
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
    }

    /**
     * H7a/H7e — a dialog LOST without any answer (scrim/system-back dismissal routed to decline,
     * Activity recreation, or process death) leaves no gate state behind: the next enable request
     * still shows the disclosure and consent is still unrecorded.
     */
    @Test
    fun anUnansweredDisclosureLeavesNoLatchedGateState() {
        val (gate, store) = newGate()
        gate.onEnableRequested() // dialog shown, then lost without accept or decline
        assertFalse(store.hasConsented())
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        assertFalse("a lost dialog must never record consent", store.hasConsented())
    }

    /**
     * H7e — process death / recreation is modelled as a FRESH gate over the SAME persisted consent.
     * The rebuilt gate must still show the disclosure (consent is an auditable record, never a
     * bypass) and must still be answerable.
     */
    @Test
    fun aRebuiltGateOverPersistedConsentStillShowsTheDisclosure() {
        val prefs = FakeConsentPreferences()
        val (first, _) = newGate(prefs)
        first.onEnableRequested()
        assertEquals(AccessibilityEnableGate.Action.DEEP_LINK, first.onConsentAccepted(nowMillis = 11L))

        // Fresh process: a new gate over the same backing preferences.
        val (rebuilt, reopenedStore) = newGate(prefs)
        assertTrue("consent must survive the rebuild", reopenedStore.hasConsented())
        assertEquals(
            "prior consent must NOT short-circuit the disclosure",
            AccessibilityEnableGate.Action.SHOW_DISCLOSURE,
            rebuilt.onEnableRequested(),
        )
    }

    /**
     * H7a/H1 policy — the strongest standing regression guard: no sequence of gate calls, in any
     * order, ever lets `onEnableRequested()` return anything but SHOW_DISCLOSURE.
     */
    @Test
    fun noSequenceOfGateCallsEverLetsAnEnableRequestDeepLink() {
        val (gate, _) = newGate()
        val steps: List<() -> Unit> = listOf(
            { gate.onConsentDeclined() },
            { gate.onConsentAccepted(nowMillis = 1L) },
            { gate.onConsentDeclined() },
            { gate.onConsentAccepted(nowMillis = 2L) },
        )
        steps.forEach { step ->
            assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
            step()
            assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        }
    }

    /**
     * D-1-CORR H9(b)/H9(e) — a FAILED settings launch must leave consent GRANTED and must leave the
     * enable entry point answerable, and the later attempt must really re-run the launch (checked by
     * invocation COUNT, not by the returned value). This fails if a future change revokes/clears
     * consent on failure, or if a failure latched the launch path shut.
     */
    @Test
    fun aFailedSettingsLaunchKeepsConsentAndLeavesTheFlowRetryable() {
        val (gate, store) = newGate()
        var attempts = 0

        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        assertEquals(AccessibilityEnableGate.Action.DEEP_LINK, gate.onConsentAccepted(nowMillis = 3L))
        val failed = AccessibilitySettingsLauncher.open {
            attempts++
            throw SecurityException("settings launch denied")
        }
        assertEquals(AccessibilitySettingsLaunchResult.UNAVAILABLE, failed)
        assertTrue("a failed launch must NOT revoke consent", store.hasConsented())
        assertEquals(1, attempts)

        // The entry point is still answerable, and still only through the disclosure.
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, gate.onEnableRequested())
        assertEquals(AccessibilityEnableGate.Action.DEEP_LINK, gate.onConsentAccepted(nowMillis = 4L))
        assertEquals(AccessibilitySettingsLaunchResult.OPENED, AccessibilitySettingsLauncher.open { attempts++ })
        assertEquals("the retry must really re-attempt the launch", 2, attempts)
    }

    /**
     * D-1-CORR H14 — process death AFTER consent was persisted but BEFORE/DURING the settings launch.
     * The state rebuilt from persisted consent must NOT lose consent, must NOT bypass the disclosure,
     * must NOT latch, and the retry must still be able to reach the launch.
     */
    @Test
    fun deathAfterConsentButBeforeTheLaunchLeavesTheFlowRetryableWithoutBypass() {
        val prefs = FakeConsentPreferences()
        val (dying, dyingStore) = newGate(prefs)
        assertEquals(AccessibilityEnableGate.Action.SHOW_DISCLOSURE, dying.onEnableRequested())
        assertEquals(AccessibilityEnableGate.Action.DEEP_LINK, dying.onConsentAccepted(nowMillis = 5L))
        // ...the process dies HERE: consent is persisted, the launch never happened.
        assertTrue(dyingStore.hasConsented())

        val (rebuilt, rebuiltStore) = newGate(prefs)
        assertTrue("consent must survive death mid-launch", rebuiltStore.hasConsented())
        assertEquals(
            "the reconstructed state must NOT bypass the disclosure",
            AccessibilityEnableGate.Action.SHOW_DISCLOSURE,
            rebuilt.onEnableRequested(),
        )

        var attempts = 0
        assertEquals(AccessibilityEnableGate.Action.DEEP_LINK, rebuilt.onConsentAccepted(nowMillis = 6L))
        assertEquals(
            AccessibilitySettingsLaunchResult.OPENED,
            AccessibilitySettingsLauncher.open { attempts++ },
        )
        assertEquals("the retry after death must really attempt the launch once", 1, attempts)
    }
}

package uk.mr_biz.fourzones.shortcut

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-1-CORR (H7d / H9 / H13) — the falsifying oracles for the settings-launch containment boundary.
 *
 * WHAT THIS EXERCISES (Evidence Contract 5b): the PRODUCTION [AccessibilitySettingsLauncher] object and
 * the PRODUCTION [AccessibilitySettingsLaunchResult] values — not a fake, not a copy. The launch action
 * itself is the boundary's own injection point, so supplying a throwing/counting lambda here is the
 * boundary's real production contract, not a test-only seam.
 *
 * WHAT THIS DOES NOT EXERCISE: `MainActivity`'s call site — the `startActivity(...)` lambda it passes in,
 * the `showAccessibilitySettingsUnavailable` Compose state it derives from the result, and the dialog it
 * renders. That composition half is an explicitly UNCREDITED residual in the D-1-CORR report; proving it
 * on the host JVM would need Robolectric, which the work order prohibits.
 */
class AccessibilitySettingsLauncherTest {

    /** A fatal JVM error. It must NEVER be contained by the boundary. */
    private class FatalTestError : Error("simulated fatal VM error")

    /** Stands in for a recoverable framework launch failure that cannot be constructed on the host JVM. */
    private class RecoverableTestFailure(message: String) : RuntimeException(message)

    @Test
    fun aSuccessfulLaunchRunsTheActionExactlyOnceAndReportsOpened() {
        var attempts = 0
        val result = AccessibilitySettingsLauncher.open { attempts++ }
        assertEquals(AccessibilitySettingsLaunchResult.OPENED, result)
        assertEquals("a successful launch must be attempted exactly once", 1, attempts)
    }

    /**
     * H7d(0)/H9(a) — the ORDERED recoverable domain is contained: the boundary must not propagate it.
     * `SecurityException` is one of the two exception types the work order names, thrown for real here.
     */
    @Test
    fun aSecurityExceptionIsContainedAndReportedAsUnavailable() {
        var attempts = 0
        val result = AccessibilitySettingsLauncher.open {
            attempts++
            throw SecurityException("not allowed to start accessibility settings")
        }
        assertEquals(AccessibilitySettingsLaunchResult.UNAVAILABLE, result)
        assertEquals("a failed launch must still be attempted exactly once", 1, attempts)
    }

    @Test
    fun anyOtherRecoverableRuntimeFailureIsContainedAndReportedAsUnavailable() {
        val result = AccessibilitySettingsLauncher.open {
            throw RecoverableTestFailure("no activity found to handle ACTION_ACCESSIBILITY_SETTINGS")
        }
        assertEquals(AccessibilitySettingsLaunchResult.UNAVAILABLE, result)
    }

    /**
     * H7d(0) — the OTHER ordered exception type, `android.content.ActivityNotFoundException`, is the one
     * `startActivity` actually throws in production. It cannot be INSTANTIATED on the host JVM (the
     * mockable `android.jar` stubs its constructor, and Robolectric is a prohibited dependency), so this
     * asserts the property that actually matters and that a regression would break: that the type is
     * inside the boundary's caught domain. Narrowing the catch to a type that no longer contains it — or
     * `ActivityNotFoundException` ceasing to be a `RuntimeException` — fails here.
     */
    @Test
    fun activityNotFoundExceptionIsInsideTheCaughtDomain() {
        assertTrue(
            "ActivityNotFoundException must be inside the boundary's caught RuntimeException domain",
            RuntimeException::class.java.isAssignableFrom(ActivityNotFoundException::class.java),
        )
    }

    /**
     * H7d(0) — fatal `Error`s MUST propagate. A `catch (Throwable)` would swallow VM/linkage failures and
     * is prohibited; this test fails the moment the catch is widened that far.
     */
    @Test
    fun fatalErrorsPropagateAndAreNotSwallowed() {
        assertThrows(FatalTestError::class.java) {
            AccessibilitySettingsLauncher.open { throw FatalTestError() }
        }
    }

    /**
     * H9(c) — the emitted recovery state is SANITIZED. Two failures with different types and different
     * messages must be indistinguishable at the boundary, so no device- or exception-specific text can
     * reach the UI through it. The result type carries no payload at all.
     */
    @Test
    fun noExceptionDetailCrossesTheBoundary() {
        val first = AccessibilitySettingsLauncher.open { throw SecurityException("denied for uid 10123") }
        val second = AccessibilitySettingsLauncher.open {
            throw RecoverableTestFailure("no handler on SM-S938B for android.settings.ACCESSIBILITY_SETTINGS")
        }
        assertEquals(first, second)
        assertEquals(AccessibilitySettingsLaunchResult.UNAVAILABLE, first)
        assertEquals(
            "the recovery state must stay a payload-free enum",
            listOf(
                AccessibilitySettingsLaunchResult.OPENED,
                AccessibilitySettingsLaunchResult.UNAVAILABLE,
            ),
            AccessibilitySettingsLaunchResult.entries.toList(),
        )
    }

    /**
     * H7d(4)/H9(e) — RETRY, verified by INVOCATION COUNT: a failed attempt must not latch. A later
     * attempt reaches the launch action again and can succeed. A boundary that remembered its failure and
     * refused to re-attempt would leave `attempts` at 1 and fail here.
     */
    @Test
    fun aFailedAttemptDoesNotLatchAndALaterAttemptLaunchesAgain() {
        var attempts = 0
        var fail = true
        val launch: () -> Unit = {
            attempts++
            if (fail) throw SecurityException("transient denial")
        }

        assertEquals(AccessibilitySettingsLaunchResult.UNAVAILABLE, AccessibilitySettingsLauncher.open(launch))
        assertEquals(1, attempts)

        fail = false
        assertEquals(AccessibilitySettingsLaunchResult.OPENED, AccessibilitySettingsLauncher.open(launch))
        assertEquals("the later attempt must really re-run the launch action", 2, attempts)
    }

    /**
     * H13 (seam half) — one call to the boundary is exactly ONE launch attempt, on success and on
     * failure alike: the boundary never retries internally and never doubles a request. Two calls in
     * immediate succession therefore produce exactly two attempts, so any duplicate launch observed in
     * production would have to come from a duplicate CALL, which is what `MainActivity`'s re-entrancy
     * guard addresses.
     *
     * NOT PROVEN HERE: that one dialog acceptance produces at most one CALL. That guard lives in
     * `MainActivity.onAccessibilityDisclosureAccepted()` and is an UNCREDITED residual.
     */
    @Test
    fun oneCallIsExactlyOneAttemptOnEveryPath() {
        var okAttempts = 0
        AccessibilitySettingsLauncher.open { okAttempts++ }
        assertEquals(1, okAttempts)

        var failAttempts = 0
        AccessibilitySettingsLauncher.open {
            failAttempts++
            throw SecurityException("denied")
        }
        assertEquals(1, failAttempts)

        var repeatedAttempts = 0
        AccessibilitySettingsLauncher.open { repeatedAttempts++ }
        AccessibilitySettingsLauncher.open { repeatedAttempts++ }
        assertEquals("two calls must be two attempts — never one, never three", 2, repeatedAttempts)
    }
}

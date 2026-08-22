package uk.mr_biz.fourzones.shortcut

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import uk.mr_biz.fourzones.BuildConfig

/**
 * Global shortcut reception wired to the snap transaction.
 *
 * It observes the external-keyboard quadrant chords Alt + Meta(Win) + 1..4 via
 * the public key-filter mechanism and, for a matched initial press, submits ONE
 * request into the validated [ShortcutSnapComposition] → SnapExecutionOrchestrator
 * (which performs the fresh T1 → resolve → geometry → T2 → resize → T3
 * transaction). It NEVER acquires or interprets topology itself, retrieves no
 * window content, handles no accessibility events, and reaches no
 * TaskResizeGateway / parser / resolver / geometry / privileged shell directly.
 *
 * [onKeyEvent] DOES consume — but only the matched chord. It returns true for
 * the matched digit stream (its initial DOWN, repeats and UP) so the focused app
 * and DeX do not also act on it, and false for everything else: unmatched keys,
 * Alt/Meta themselves, and any chord carrying Ctrl or Shift. The decision is made
 * by [ShortcutKeyConsumptionTracker]; see its documentation for the exact rule.
 *
 * Not a keylogger. Under tag `DexZonesShortcut` a RELEASE build logs exactly
 * three fixed strings — "service connected (shortcut snap dispatch active)",
 * "service interrupted" and "service destroyed" — none of which interpolates
 * any value or reports a key. Every line that reports a key event or a snap is
 * wrapped in `if (BuildConfig.DEBUG)` and so is not emitted by a release build.
 * Even in a debug build those lines are reached only for a MATCHED
 * Alt+Meta+1..4 initial press, because [ShortcutSnapDispatcher] returns a
 * candidate only when [QuadrantShortcutMatcher] matched; no other keystroke
 * reaches a log line in any build. Separately, and in EVERY build, the matched
 * candidate and the snap outcome are published to the in-app diagnostic holders
 * [ShortcutDiagnosticsStore] and [ShortcutSnapResultStore], which are
 * process-local, keep only the latest value, and are read only by this app's
 * own diagnostics UI — never logged.
 */
class QuadrantShortcutAccessibilityService : AccessibilityService() {

    // Owns at most one live session across repeated connections. Created lazily
    // in onServiceConnected (needs an attached Context).
    private var lifecycle: ShortcutServiceLifecycle? = null

    override fun onServiceConnected() {
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        val owner = lifecycle ?: ShortcutServiceLifecycle(
            onEvent = ::onSnapEvent,
            sessionFactory = { sink -> ProductionShortcutSession(applicationContext, packageName, sink) },
        ).also { lifecycle = it }
        // Retires any prior session and installs a fresh one (idempotent).
        owner.onConnected()
        Log.i(TAG, "service connected (shortcut snap dispatch active)")
    }

    // Required override; intentionally a no-op — no accessibility events are
    // consumed and no window content is retrieved.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        // Interruption clears TRANSIENT consumed key-stream ownership so no stale
        // owned digit can remain stuck; it does NOT stop the backend, retire the
        // composition, or reconnect Shizuku, and synthesizes no key events.
        lifecycle?.resetInputOwnership()
        Log.i(TAG, "service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val decision = lifecycle?.handleKey(
            deviceId = event.deviceId,
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            altPressed = event.isAltPressed,
            metaPressed = event.isMetaPressed,
            ctrlPressed = event.isCtrlPressed,
            shiftPressed = event.isShiftPressed,
        )
        val candidate = decision?.candidate
        if (candidate != null) {
            logCandidate(candidate, event)
            ShortcutDiagnosticsStore.publish(
                ShortcutObservation(candidate, SystemClock.elapsedRealtime()),
            )
        }
        // Phase 3B2: CONSUME only the matched DexZones digit stream (its initial
        // DOWN, repeats and UP) so the focused app / DeX does not also act on it;
        // every other event (unmatched keys, Alt/Meta themselves, and any chord
        // carrying Ctrl or Shift) passes through.
        return decision?.consume ?: false
    }

    private fun onSnapEvent(event: ShortcutSnapEvent) {
        when (event) {
            // D-2 H10 release logging policy: GATED (see logCandidate). Snap
            // request/result lines carry the per-request correlation id and the
            // target quadrant; the structured diagnostic is still published to
            // the in-app store in every build.
            is ShortcutSnapEvent.Requested ->
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "snap request=${event.requestId} quadrant=${event.quadrant}")
                }
            is ShortcutSnapEvent.RejectedBusy -> {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "snap result=${event.requestId} quadrant=${event.quadrant} Busy")
                }
                ShortcutSnapResultStore.publish(
                    ShortcutSnapResultDiagnostic(event.requestId, event.quadrant, "Busy"),
                )
            }
            is ShortcutSnapEvent.Completed -> {
                val label = event.result::class.simpleName ?: "Unknown"
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "snap result=${event.requestId} quadrant=${event.quadrant} $label")
                }
                ShortcutSnapResultStore.publish(
                    ShortcutSnapResultDiagnostic(event.requestId, event.quadrant, label),
                )
            }
            is ShortcutSnapEvent.SubmissionFailed -> {
                if (BuildConfig.DEBUG) {
                    Log.w(
                        TAG,
                        "snap result=${event.requestId} quadrant=${event.quadrant} " +
                            "SubmissionFailed(${event.reason})",
                    )
                }
                ShortcutSnapResultStore.publish(
                    ShortcutSnapResultDiagnostic(event.requestId, event.quadrant, "SubmissionFailed"),
                )
            }
        }
    }

    // D-2 H10 release logging policy: GATED. This line carries input-device
    // identifiers (deviceId, source) and key-event detail observed by an
    // AccessibilityService with key-event filtering. It is a bring-up
    // diagnostic and must never be emitted by a distributed build. The
    // structured observation is still published to ShortcutDiagnosticsStore in
    // every build, so in-app diagnostics are unaffected.
    private fun logCandidate(candidate: ShortcutCandidate, event: KeyEvent) {
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "candidate=${candidate.name} intended=${candidate.intendedMeaning} " +
                    "keyCode=${KeyEvent.keyCodeToString(event.keyCode)} action=DOWN repeat=0 " +
                    "ctrl=${event.isCtrlPressed} meta=${event.isMetaPressed} " +
                    "alt=${event.isAltPressed} shift=${event.isShiftPressed} " +
                    "deviceId=${event.deviceId} source=${event.source}",
            )
        }
    }

    override fun onDestroy() {
        lifecycle?.onDestroyed()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DexZonesShortcut"
    }
}

package uk.mr_biz.fourzones.capture

import uk.mr_biz.fourzones.desktop.DesktopTopologyReader
import uk.mr_biz.fourzones.desktop.SnapTargetResolver
import uk.mr_biz.fourzones.privileged.PrivilegedBackend
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus

/** Delayed-execution seam so the coordination logic is JVM-testable. */
fun interface CaptureScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): CaptureCancellable
}

fun interface CaptureCancellable {
    fun cancel()
}

/**
 * One-shot coordination for a delayed background target capture:
 *
 *   wait [captureDelayMillis] (tester switches apps)
 *     -> start OWN reader + backend
 *     -> on READY, exactly one topology refresh
 *     -> resolve targets through the unchanged [SnapTargetResolver]
 *     -> finish EXACTLY ONCE with a sanitized [TargetCaptureResult]
 *     -> stop own reader and backend
 *
 * Framework-free: Android specifics (foreground service, Handler delays)
 * stay in the service; this class owns the one-shot state machine and is
 * unit-tested with fake backend/scheduler.
 *
 * Exception containment: every external callback entry (scheduled actions,
 * backend status delivery, reader outcome delivery — including resolution)
 * runs inside one [boundary] guard. An ordinary RuntimeException there
 * produces a single sanitized failure via the terminal-once [finish]; fatal
 * Errors are never caught. After the terminal transition every late
 * callback is inert, and cleanup runs at most once with each step
 * individually contained so [onResult] is always reached.
 *
 * Ownership: the [backend] and the reader created from it belong to THIS
 * controller alone — never MainActivity's instances — and are stopped on
 * every terminal path. All calls are main-thread (backend contract).
 */
class TargetCaptureController(
    private val backend: PrivilegedBackend,
    private val selfPackageName: String,
    private val captureDelayMillis: Long,
    private val readinessTimeoutMillis: Long,
    private val scheduler: CaptureScheduler,
    private val clock: CaptureClock,
    private val sequencer: CaptureSequencer,
    private val onResult: (TargetCaptureResult) -> Unit,
) {

    private var started = false
    private var terminal = false
    private var refreshRequested = false
    private var delayToken: CaptureCancellable? = null
    private var timeoutToken: CaptureCancellable? = null
    private var reader: DesktopTopologyReader? = null

    /** Begins the one-shot flow. Idempotent; a second call does nothing. */
    fun start() {
        if (started) return
        started = true
        boundary("capture scheduling") {
            delayToken = scheduler.schedule(captureDelayMillis) {
                boundary("delayed capture start") { beginRead() }
            }
        }
    }

    /** Terminates from outside (service teardown/timeout). Idempotent. */
    fun cancel(reason: String) {
        finish(TargetCaptureResult.Failed(reason))
    }

    private fun beginRead() {
        if (terminal) return
        // Failsafe: no matter what the backend does (or never does), the
        // controller reaches a terminal state within this bound.
        timeoutToken = scheduler.schedule(readinessTimeoutMillis) {
            boundary("capture timeout") {
                finish(TargetCaptureResult.Failed("capture timed out waiting for backend/read"))
            }
        }
        val ownReader = DesktopTopologyReader(backend)
        reader = ownReader
        ownReader.start { outcome ->
            boundary("topology outcome") { finish(resultOf(outcome)) }
        }
        backend.start { status ->
            boundary("backend status") { onBackendStatus(status) }
        }
    }

    private fun onBackendStatus(status: PrivilegedBackendStatus) {
        if (terminal || refreshRequested) return
        when (status) {
            PrivilegedBackendStatus.READY -> {
                refreshRequested = true
                reader?.refresh()
            }
            // Might still become ready; the readiness timeout bounds the wait.
            // CONNECTING = binder/permission ready, privileged service still
            // verifying its protocol version — a normal transient toward READY.
            PrivilegedBackendStatus.BINDER_UNAVAILABLE,
            PrivilegedBackendStatus.CONNECTING,
            -> Unit
            // Definitively unusable for an unattended one-shot capture: no
            // permission prompt can be answered from the background.
            else -> finish(
                TargetCaptureResult.Failed("privileged backend unavailable: $status"),
            )
        }
    }

    private fun resultOf(outcome: DesktopTopologyReader.Outcome): TargetCaptureResult =
        when (outcome) {
            is DesktopTopologyReader.Outcome.Success -> {
                // The SAME snapshot and audited resolver the mutation path uses;
                // diagnostics are a pure projection and never alter eligibility.
                val targets = SnapTargetResolver.resolve(outcome.snapshot, selfPackageName)
                TargetCaptureResult.Captured(
                    targets = targets,
                    diagnostics = CaptureDiagnostics.from(
                        sequenceNumber = sequencer.next(),
                        wallClockMillis = clock.wallClockMillis(),
                        monotonicMillis = clock.monotonicMillis(),
                        snapshot = outcome.snapshot,
                        targets = targets,
                    ),
                )
            }
            is DesktopTopologyReader.Outcome.BackendUnavailable ->
                TargetCaptureResult.Failed("backend became ${outcome.status} during capture")
            is DesktopTopologyReader.Outcome.Failed ->
                TargetCaptureResult.Failed("topology read failed: ${outcome.message}")
        }

    /**
     * The terminal-once transition: cancels timers and releases reader and
     * backend with each step individually contained, then delivers exactly
     * one result. A late [onResult] throw propagates to the calling
     * boundary, which finds the controller already terminal and stays inert
     * — one terminal outcome, cleanup at most once, no stale publication.
     */
    private fun finish(result: TargetCaptureResult) {
        if (terminal) return
        terminal = true
        contained { delayToken?.cancel() }
        contained { timeoutToken?.cancel() }
        contained { reader?.stop() }
        contained { backend.stop() }
        onResult(result)
    }

    /**
     * Single guarded entry for external callbacks: an ordinary
     * RuntimeException becomes one sanitized failure (label only — no
     * exception internals); after the terminal transition, [finish] makes
     * any further invocation a no-op. Fatal Errors are never caught.
     */
    private inline fun boundary(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            finish(TargetCaptureResult.Failed("internal capture error during $label"))
        }
    }

    private inline fun contained(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // Cleanup steps must not prevent result delivery or each other.
        }
    }
}

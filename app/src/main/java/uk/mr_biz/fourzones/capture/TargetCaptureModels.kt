package uk.mr_biz.fourzones.capture

import uk.mr_biz.fourzones.desktop.DesktopTopologySnapshot
import uk.mr_biz.fourzones.desktop.DisplayFocus
import uk.mr_biz.fourzones.desktop.SnapTargetAssessment

/**
 * Result of one background target-capture diagnostic. Sanitized by
 * construction: it carries only the structured [SnapTargetAssessment] map
 * (session-local IDs, package/component names, reason strings) — never
 * filtered or raw topology text.
 */
sealed interface TargetCaptureResult {

    /**
     * The delayed topology read succeeded; per-display target assessments plus
     * additive [CaptureDiagnostics]. The [diagnostics] are a pure projection of
     * [targets] and the snapshot's focus evidence — they are NEVER read back
     * into eligibility (the resolver decides from the snapshot alone).
     */
    data class Captured(
        val targets: Map<Int, SnapTargetAssessment>,
        val diagnostics: CaptureDiagnostics,
    ) : TargetCaptureResult

    /** The capture could not produce an assessment; diagnostic reason. */
    data class Failed(val reason: String) : TargetCaptureResult
}

/** Coarse outcome kind for one display, for compact diagnostics. */
enum class CaptureResultKind { FOUND, NO_TARGET, AMBIGUOUS, UNSUPPORTED }

/** What the capture resolved for a single display (diagnostic projection). */
data class CaptureDisplayDiagnostic(
    val displayId: Int,
    val result: CaptureResultKind,
    /** Present only when [result] is [CaptureResultKind.FOUND]. */
    val taskId: Int?,
    /** Present only when [result] is [CaptureResultKind.FOUND]. */
    val packageName: String?,
)

/**
 * The focus signals the resolver actually saw, surfaced verbatim so a hardware
 * run reveals WHY a target was or was not found — without exposing raw dump
 * text. This is the instrument for the display-scoped-focus investigation:
 * whether the WindowManager scoped-focus grammar reached the parser
 * ([hasDisplayScopedFocusEvidence]), how many physical displays the dump
 * showed ([observedDisplayIds]), the legacy global focus, and each display's
 * scoped focus.
 */
data class CaptureFocusEvidence(
    val hasDisplayScopedFocusEvidence: Boolean,
    val observedDisplayIds: List<Int>,
    val globalFocusedTaskId: Int?,
    /** displayId -> "task <n>" or "conflicting". */
    val scopedFocusByDisplay: Map<Int, String>,
)

/**
 * Additive, sanitized metadata for one successful capture. Purely diagnostic:
 * nothing here influences target eligibility (it is derived FROM the resolved
 * assessments and the snapshot, never fed back into resolution).
 */
data class CaptureDiagnostics(
    /** Process-global, monotonically increasing capture ordinal. */
    val sequenceNumber: Long,
    /** Epoch millis when the capture completed; for human-readable time only. */
    val wallClockMillis: Long,
    /** Monotonic millis at completion (e.g. elapsedRealtime); for age math. */
    val monotonicMillis: Long,
    val perDisplay: List<CaptureDisplayDiagnostic>,
    val focusEvidence: CaptureFocusEvidence,
) {
    /** Age from a MONOTONIC clock reading; never computed from wall-clock. */
    fun ageMillis(nowMonotonicMillis: Long): Long =
        (nowMonotonicMillis - monotonicMillis).coerceAtLeast(0)

    companion object {
        /** Builds diagnostics as a pure projection of the resolved [targets]. */
        fun from(
            sequenceNumber: Long,
            wallClockMillis: Long,
            monotonicMillis: Long,
            snapshot: DesktopTopologySnapshot,
            targets: Map<Int, SnapTargetAssessment>,
        ): CaptureDiagnostics = CaptureDiagnostics(
            sequenceNumber = sequenceNumber,
            wallClockMillis = wallClockMillis,
            monotonicMillis = monotonicMillis,
            perDisplay = targets.map { (displayId, assessment) ->
                when (assessment) {
                    is SnapTargetAssessment.Found -> CaptureDisplayDiagnostic(
                        displayId, CaptureResultKind.FOUND,
                        assessment.targetTaskId, assessment.packageName,
                    )
                    is SnapTargetAssessment.NoTarget ->
                        CaptureDisplayDiagnostic(displayId, CaptureResultKind.NO_TARGET, null, null)
                    is SnapTargetAssessment.Ambiguous ->
                        CaptureDisplayDiagnostic(displayId, CaptureResultKind.AMBIGUOUS, null, null)
                    is SnapTargetAssessment.Unsupported ->
                        CaptureDisplayDiagnostic(displayId, CaptureResultKind.UNSUPPORTED, null, null)
                }
            },
            focusEvidence = CaptureFocusEvidence(
                hasDisplayScopedFocusEvidence = snapshot.hasDisplayScopedFocusEvidence,
                observedDisplayIds = snapshot.observedDisplayIds.sorted(),
                globalFocusedTaskId = snapshot.focusedTaskId,
                scopedFocusByDisplay = snapshot.focusedTaskByDisplay.mapValues { (_, focus) ->
                    when (focus) {
                        is DisplayFocus.Task -> "task ${focus.taskId}"
                        DisplayFocus.Conflicting -> "conflicting"
                    }
                },
            ),
        )
    }
}

/** Monotonic + wall clock seam so the controller stays JVM-testable. */
interface CaptureClock {
    /** Epoch millis; human-readable capture time only, never age math. */
    fun wallClockMillis(): Long

    /** Monotonic millis (e.g. SystemClock.elapsedRealtime); for age math. */
    fun monotonicMillis(): Long
}

/** Process-global capture ordinal source (injected so tests are deterministic). */
fun interface CaptureSequencer {
    fun next(): Long
}

/** Compact single-line sanitized description (for the one Logcat diagnostic). */
fun TargetCaptureResult.describeForLog(): String = when (this) {
    is TargetCaptureResult.Failed -> "FAILED: $reason"
    is TargetCaptureResult.Captured -> {
        val body = if (targets.isEmpty()) {
            "CAPTURED: no display with desk topology"
        } else {
            targets.entries.joinToString("; ") { (displayId, target) ->
                "display $displayId -> " + when (target) {
                    is SnapTargetAssessment.Found ->
                        "FOUND task=${target.targetTaskId} root=${target.activeDeskRootId} " +
                            "package=${target.packageName ?: "?"}"
                    is SnapTargetAssessment.NoTarget -> "NO TARGET: ${target.reason}"
                    is SnapTargetAssessment.Ambiguous -> "AMBIGUOUS: ${target.reason}"
                    is SnapTargetAssessment.Unsupported -> "UNSUPPORTED: ${target.reason}"
                }
            }
        }
        "$body | ${diagnostics.describeForLog()}"
    }
}

/** Compact focus-evidence + sequencing line; the display-scoped-focus probe. */
fun CaptureDiagnostics.describeForLog(): String {
    val e = focusEvidence
    val scoped = if (e.scopedFocusByDisplay.isEmpty()) {
        "none"
    } else {
        e.scopedFocusByDisplay.entries.joinToString(",") { (d, f) -> "$d=$f" }
    }
    return "seq=$sequenceNumber t=$wallClockMillis " +
        "scopedGrammar=${e.hasDisplayScopedFocusEvidence} " +
        "displays=${e.observedDisplayIds} globalFocus=${e.globalFocusedTaskId ?: "none"} " +
        "scopedFocus=$scoped"
}

/**
 * Process-local holder for the most recent one-shot capture result.
 *
 * Lifecycle rationale: during a capture the foreground service keeps the
 * process (and therefore this holder) alive; afterwards the result only
 * needs to survive until the user returns to the diagnostics UI in the same
 * process. Nothing is persisted — an OS process kill loses the result, which
 * is acceptable for a diagnostic seam and avoids persisting topology data.
 *
 * Threading: publish/setListener/lastResult are main-thread only.
 */
object TargetCaptureResultStore {

    @Volatile
    private var result: TargetCaptureResult? = null
    private var listener: ((TargetCaptureResult) -> Unit)? = null

    val lastResult: TargetCaptureResult? get() = result

    fun publish(newResult: TargetCaptureResult) {
        result = newResult
        listener?.invoke(newResult)
    }

    /** UI registers while started to observe results arriving live; null to clear. */
    fun setListener(newListener: ((TargetCaptureResult) -> Unit)?) {
        listener = newListener
    }
}

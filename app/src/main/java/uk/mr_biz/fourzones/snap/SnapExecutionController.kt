package uk.mr_biz.fourzones.snap

import uk.mr_biz.fourzones.geometry.Quadrant

/** Delayed-execution seam so the lifecycle is JVM-testable without Android. */
fun interface DelayedScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): DelayedCancellable
}

fun interface DelayedCancellable {
    fun cancel()
}

/**
 * Diagnostic delayed one-shot snap execution: the user presses a quadrant
 * button, switches to the intended app, and at TIMER FIRE (not at press) the
 * [orchestrator] does everything fresh. Because pressing a button inside
 * DexZones changes focus, capturing the target at press would be wrong — the
 * capture happens at fire.
 *
 * Lifecycle guarantees (single-threaded; all calls and callbacks on the main
 * thread), mirroring the proven Phase 2C1 capture design:
 *  - only one execution may be pending; [requestSnap] cancels/replaces any
 *    prior pending action deterministically;
 *  - a monotonic generation guards the fired timer and the orchestrator
 *    result: a stale (cancelled/superseded) firing or result can never
 *    trigger or publish a mutation;
 *  - [stop] invalidates all outstanding generations and drops the callback,
 *    so a late timer or result after stop is inert.
 *
 * No recurring execution.
 */
class SnapExecutionController(
    private val orchestrator: SnapExecutionOrchestrator,
    private val delayMillis: Long,
    private val scheduler: DelayedScheduler,
    private val onStateChanged: (SnapExecutionState) -> Unit,
) {

    private var started = false
    private var generation = 0L
    private var pendingToken: DelayedCancellable? = null

    fun start() {
        started = true
    }

    fun stop() {
        started = false
        cancelPending()
        // Bump so any already-fired timer or in-flight result is stale.
        generation++
        onStateChanged(SnapExecutionState.Idle)
    }

    /** Schedules one delayed snap for [quadrant], replacing any pending one. */
    fun requestSnap(quadrant: Quadrant) {
        if (!started) return
        cancelPending()
        val g = ++generation
        onStateChanged(SnapExecutionState.Pending(quadrant))
        pendingToken = scheduler.schedule(delayMillis) {
            if (!started || g != generation) return@schedule
            pendingToken = null
            onStateChanged(SnapExecutionState.Executing(quadrant))
            // The predicate is evaluated by the orchestrator immediately before
            // the mutation: a replacement (new generation) or stop before that
            // boundary terminates WITHOUT mutating, not merely suppresses the
            // published result.
            orchestrator.execute(
                quadrant = quadrant,
                isCancelled = { !started || g != generation },
            ) { result ->
                if (!started || g != generation) return@execute
                onStateChanged(SnapExecutionState.Completed(result))
            }
        }
    }

    private fun cancelPending() {
        pendingToken?.cancel()
        pendingToken = null
    }
}

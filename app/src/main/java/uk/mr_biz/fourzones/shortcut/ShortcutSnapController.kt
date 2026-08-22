package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.snap.SnapExecutionResult

/**
 * Narrow execution seam over the EXISTING validated snap transaction. It is the
 * ONLY thing [ShortcutSnapController] can reach downstream — the controller has
 * no reference to `TaskResizeGateway`, the parser, the resolver, the geometry
 * calculator, or the privileged shell. The production implementation simply
 * forwards to `SnapExecutionOrchestrator.execute`, so every shortcut runs the
 * same fresh T1 → resolve → geometry → T2 → resize → T3 transaction.
 */
fun interface ShortcutSnapExecutor {
    fun execute(
        quadrant: Quadrant,
        isCancelled: () -> Boolean,
        onResult: (SnapExecutionResult) -> Unit,
    )
}

/** Diagnostic events emitted for one shortcut snap request. */
sealed interface ShortcutSnapEvent {
    val requestId: Long
    val quadrant: Quadrant

    /** A request was accepted for correlation (logged as `request=<id>`). */
    data class Requested(override val requestId: Long, override val quadrant: Quadrant) :
        ShortcutSnapEvent

    /**
     * Dropped because a snap transaction is already in flight. NOT queued and
     * NOT replayed later — the user simply presses again if desired.
     */
    data class RejectedBusy(override val requestId: Long, override val quadrant: Quadrant) :
        ShortcutSnapEvent

    /** The existing orchestrator finished; [result] is its verbatim outcome. */
    data class Completed(
        override val requestId: Long,
        override val quadrant: Quadrant,
        val result: SnapExecutionResult,
    ) : ShortcutSnapEvent

    /**
     * The submission itself threw synchronously (an internal/executor error) —
     * NEVER mislabeled as a snap outcome. Fail closed; the slot is freed so a
     * later shortcut works.
     */
    data class SubmissionFailed(
        override val requestId: Long,
        override val quadrant: Quadrant,
        val reason: String,
    ) : ShortcutSnapEvent
}

/**
 * One-shot, non-queuing controller that turns a matched shortcut into exactly
 * one call into the existing snap transaction, serialized so two mutations can
 * never race.
 *
 * Semantics:
 *  - each accepted request emits [ShortcutSnapEvent.Requested] then exactly one
 *    terminal event ([RejectedBusy], [Completed], or [SubmissionFailed]);
 *  - a request received while the backend is not READY is rejected IMMEDIATELY
 *    with a terminal [Completed] carrying [SnapExecutionResult.PrivilegeUnavailable]
 *    — the executor is NEVER called (so the orchestrator's read can never be
 *    queued and later replayed), and the request is not retained;
 *  - while a transaction is in flight, a new request is REJECTED as busy — never
 *    queued, delayed, replayed, or persisted;
 *  - a synchronous throw from the executor is contained: it never escapes to the
 *    caller, frees the slot, and emits exactly one terminal [SubmissionFailed];
 *  - a monotonic generation plus an active flag make a stale result (after
 *    [stop]/restart) inert, and pass the orchestrator an `isCancelled` predicate
 *    so a stop before the mutation boundary terminates WITHOUT mutating.
 *
 * The controller neither reads topology nor knows any coordinates; it only
 * chooses whether to submit one request to [executor] and reports the result.
 * The [readiness] supplier is the SAME verified-service truth that permits
 * mutation. All methods run on the owning main thread.
 */
class ShortcutSnapController(
    private val readiness: () -> PrivilegedBackendStatus,
    private val executor: ShortcutSnapExecutor,
    private val onEvent: (ShortcutSnapEvent) -> Unit,
) {

    private var active = false
    private var generation = 0L
    private var inFlight = false
    private var nextRequestId = 0L

    fun start() {
        active = true
    }

    fun stop() {
        active = false
        // Invalidate any in-flight transaction's result and free the slot; a
        // late orchestrator callback for the old generation is then inert.
        generation++
        inFlight = false
    }

    /** Submits at most one snap for [quadrant]; a concurrent request is busy. */
    fun requestSnap(quadrant: Quadrant) {
        if (!active) return
        val requestId = ++nextRequestId
        onEvent(ShortcutSnapEvent.Requested(requestId, quadrant))
        if (inFlight) {
            onEvent(ShortcutSnapEvent.RejectedBusy(requestId, quadrant))
            return
        }
        // Gate on the verified-service truth BEFORE the orchestrator, so a
        // non-ready shortcut fails terminally now and is never queued/replayed.
        val status = readiness()
        if (status != PrivilegedBackendStatus.READY) {
            onEvent(
                ShortcutSnapEvent.Completed(
                    requestId,
                    quadrant,
                    SnapExecutionResult.PrivilegeUnavailable(quadrant, status),
                ),
            )
            return
        }
        inFlight = true
        val g = ++generation
        // `terminal` guarantees exactly one terminal event even for a faulty
        // executor that calls back AND throws, or throws before calling back.
        var terminal = false
        try {
            executor.execute(
                quadrant = quadrant,
                isCancelled = { !active || g != generation },
            ) { result ->
                // Reject a stale callback (stopped or superseded by a new session)
                // or a duplicate callback for this request.
                if (!active || g != generation || terminal) return@execute
                terminal = true
                inFlight = false
                onEvent(ShortcutSnapEvent.Completed(requestId, quadrant, result))
            }
        } catch (e: RuntimeException) {
            // Synchronous submission failure. If the callback already delivered a
            // terminal (callback-then-throw), do nothing — exactly one terminal.
            if (!terminal) {
                terminal = true
                inFlight = false
                onEvent(
                    ShortcutSnapEvent.SubmissionFailed(requestId, quadrant, e.javaClass.simpleName),
                )
            }
        }
    }
}

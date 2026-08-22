package uk.mr_biz.fourzones.desktop

import uk.mr_biz.fourzones.privileged.PrivilegedBackend
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.privileged.TopologyReadResult

/**
 * Lifecycle- and ordering-aware coordinator for topology reads: asks the
 * [PrivilegedBackend] for the named read-only dump and publishes immutable
 * [DesktopTopologySnapshot] outcomes.
 *
 * This is the ONLY way the rest of the app obtains topology data. It cannot
 * run arbitrary commands — the backend interface has none to offer.
 *
 * Ordering/lifecycle contract (single-threaded: all methods and backend
 * callbacks run on the main thread):
 *  - every read gets a monotonically increasing generation;
 *  - ONLY the newest generation may publish — an older read finishing after
 *    a newer one is discarded, so results can never go backwards, and a
 *    stale failure (e.g. from a binder death) can never overwrite a newer
 *    success. READY-triggered automatic reads and manual refreshes go
 *    through the same rule;
 *  - [stop] invalidates every outstanding generation and drops the outcome
 *    callback, so in-flight results arriving after stop are discarded and
 *    nothing captured by the callback (e.g. an Activity) is retained;
 *  - [start] after [stop] begins a fresh generation domain: generations
 *    keep increasing monotonically, so reads issued before the stop can
 *    never publish into the new session.
 */
class DesktopTopologyReader(private val backend: PrivilegedBackend) {

    sealed interface Outcome {
        data class Success(val snapshot: DesktopTopologySnapshot) : Outcome
        data class BackendUnavailable(val status: PrivilegedBackendStatus) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private var started = false
    private var onOutcome: ((Outcome) -> Unit)? = null
    private var currentGeneration = 0L

    /** Begins publishing outcomes to [onOutcome]. Idempotent. */
    fun start(onOutcome: (Outcome) -> Unit) {
        started = true
        this.onOutcome = onOutcome
    }

    /**
     * Invalidates all outstanding reads and releases the outcome callback.
     * Results arriving after this point are discarded.
     */
    fun stop() {
        started = false
        onOutcome = null
        // Consume a generation so every read issued before stop() is stale
        // even if start() is called again before it completes.
        currentGeneration++
    }

    /** Requests a fresh topology read. No-op unless started. */
    fun refresh() {
        if (!started) return
        val generation = ++currentGeneration
        backend.readActivityTopology { result -> deliver(generation, result) }
    }

    private fun deliver(generation: Long, result: TopologyReadResult) {
        if (!started) return // callback after stop: discard
        if (generation != currentGeneration) return // superseded: discard
        val outcome = when (result) {
            is TopologyReadResult.Success ->
                Outcome.Success(DesktopTopologyParser.parse(result.filteredDump))
            is TopologyReadResult.BackendUnavailable ->
                Outcome.BackendUnavailable(result.status)
            is TopologyReadResult.CommandFailed ->
                Outcome.Failed(result.message)
        }
        onOutcome?.invoke(outcome)
    }
}

package uk.mr_biz.fourzones.shortcut

/**
 * One matched shortcut observation, for the diagnostic UI only. [monotonicMillis]
 * is an elapsed-realtime stamp (not wall clock). Nothing here is queued,
 * replayed, or persisted.
 */
data class ShortcutObservation(
    val candidate: ShortcutCandidate,
    val monotonicMillis: Long,
)

/**
 * Process-local holder for the most recent matched shortcut.
 *
 * THIS HOLDER is a diagnostic seam and nothing more: publishing to it triggers
 * no action, and what it holds is never queued, replayed, or persisted (a process
 * kill loses it). It has no connection to the snap engine, TaskResizeGateway, or
 * Shizuku.
 *
 * A MATCHED SHORTCUT, however, does more than update this holder. The same key
 * callback that publishes here also submits a snap request, and that request ends
 * in a privileged task resize — by a path that does not run through this object.
 * Main-thread only (publish from the AccessibilityService key callback, observe
 * from the UI).
 */
object ShortcutDiagnosticsStore {

    @Volatile
    private var last: ShortcutObservation? = null
    private var listener: ((ShortcutObservation) -> Unit)? = null

    val lastObservation: ShortcutObservation? get() = last

    fun publish(observation: ShortcutObservation) {
        last = observation
        listener?.invoke(observation)
    }

    /** UI registers while visible to observe live matches; null to clear. */
    fun setListener(newListener: ((ShortcutObservation) -> Unit)?) {
        listener = newListener
    }
}

package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.geometry.Quadrant

/**
 * The most recent shortcut snap outcome, for the diagnostic UI. [label] is the
 * existing result-type name (e.g. "AppliedAndVerified", "NoTarget",
 * "PrivilegeUnavailable") or "Busy"; no raw topology, task/display IDs, or
 * history is exposed as product policy.
 */
data class ShortcutSnapResultDiagnostic(
    val requestId: Long,
    val quadrant: Quadrant,
    val label: String,
)

/**
 * Process-local holder for the last shortcut snap outcome. Diagnostic only:
 * nothing is queued, replayed, or persisted (a process kill loses it), and this
 * holder never triggers a mutation — it is written AFTER the existing
 * transaction reports its result. Main-thread only.
 */
object ShortcutSnapResultStore {

    @Volatile
    private var last: ShortcutSnapResultDiagnostic? = null
    private var listener: ((ShortcutSnapResultDiagnostic) -> Unit)? = null

    val lastResult: ShortcutSnapResultDiagnostic? get() = last

    fun publish(result: ShortcutSnapResultDiagnostic) {
        last = result
        listener?.invoke(result)
    }

    fun setListener(newListener: ((ShortcutSnapResultDiagnostic) -> Unit)?) {
        listener = newListener
    }
}

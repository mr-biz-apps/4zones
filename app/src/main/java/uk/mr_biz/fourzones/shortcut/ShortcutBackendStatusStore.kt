package uk.mr_biz.fourzones.shortcut

import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus

/**
 * Process-local holder for the CURRENT status of the shortcut path's OWN
 * privileged backend (the one owned by the AccessibilityService's
 * [ShortcutSnapComposition]) — never MainActivity's separate backend.
 *
 * `null` means "no currently published shortcut-backend observation" (no live
 * session, starting, or unknown). A consumer must treat `null` conservatively;
 * it can never mean ready.
 *
 * Like the other diagnostic stores: main-thread only, nothing is queued,
 * replayed, or persisted (a process kill resets it to `null`), and reading or
 * observing it never triggers any backend/lifecycle action. The single writer
 * is the at-most-one live shortcut session guaranteed by
 * [ShortcutServiceLifecycle]; the session publishes real status while running
 * and clears to `null` as the FINAL observation after its backend is stopped
 * (see [ShortcutSnapComposition.stop] for the ordering contract).
 */
object ShortcutBackendStatusStore {

    @Volatile
    private var latestStatus: PrivilegedBackendStatus? = null
    private var listener: ((PrivilegedBackendStatus?) -> Unit)? = null

    /** The most recent observation; `null` = none/starting/unknown, never ready. */
    fun latest(): PrivilegedBackendStatus? = latestStatus

    fun publish(status: PrivilegedBackendStatus) {
        latestStatus = status
        listener?.invoke(status)
    }

    /** Publishes `null` as the final observation of a stopped session. */
    fun clear() {
        latestStatus = null
        listener?.invoke(null)
    }

    /**
     * Registers the single observer (the visible UI), or removes it with
     * `null`. A removed listener is never invoked again, so a stopped Activity
     * is neither retained nor called.
     */
    fun setListener(newListener: ((PrivilegedBackendStatus?) -> Unit)?) {
        listener = newListener
    }
}

package uk.mr_biz.fourzones.privileged

/**
 * Monotonic backend lifecycle/session token — DISTINCT from the coordinator's
 * per-connection generation.
 *
 * It stamps the GLOBAL Shizuku listeners (binder-received / binder-death /
 * permission-result), which — unlike per-connection `ServiceConnection`
 * callbacks — carry no generation of their own. A death notification queued
 * while lifecycle A was active could otherwise fire after a stop/start and
 * invalidate a healthy lifecycle B. Each `start()` opens a new session; a
 * listener captures its session token and acts only while that token is still
 * the active one, so a callback from a prior session is inert.
 *
 * The token only ever advances and is never reset, so a closed session's token
 * can never be mistaken for a current one. Separate from the connection
 * generation on purpose (they guard different callback sources).
 */
class BackendSessionGate {

    private var current = 0L

    /** Opens a new session and returns its token (advances monotonically). */
    fun open(): Long = ++current

    /** Closes the current session so its token is no longer active. */
    fun close() {
        ++current
    }

    /** True only while [token] is the currently open session. */
    fun isActive(token: Long): Boolean = token == current
}

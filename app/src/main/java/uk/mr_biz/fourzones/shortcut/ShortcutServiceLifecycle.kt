package uk.mr_biz.fourzones.shortcut

/**
 * One live shortcut session: the composition (its own backend + orchestrator)
 * plus the key dispatcher. Abstracted so the lifecycle owner is unit-testable
 * without instantiating the AccessibilityService.
 */
interface ShortcutSession {
    fun start()
    fun stop()

    /**
     * Forgets transient consumed key-stream ownership WITHOUT tearing down the
     * privileged backend/composition. Used on AccessibilityService interruption.
     */
    fun resetInputOwnership()

    /**
     * Evaluate a key event: submit at most one snap on a matched initial DOWN and
     * decide whether to CONSUME the event (reserve the DexZones chord). Returns
     * the matched candidate (for logging) and the consume decision.
     */
    fun handleKey(
        deviceId: Int,
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        altPressed: Boolean,
        metaPressed: Boolean,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
    ): ShortcutKeyDecision
}

/**
 * Owns AT MOST ONE live [ShortcutSession] across repeated AccessibilityService
 * connections, so a reconnect can never leave two backends/controllers able to
 * mutate.
 *
 * Each [onConnected] advances a monotonic lifecycle generation BEFORE building
 * the new session, retires the previous session exactly once, and hands the new
 * session an event sink stamped with the new generation. A late event from a
 * retired session therefore carries an old generation and is dropped — it can
 * neither publish a result nor disturb the current session (whose controller is
 * a separate instance with its own `inFlight`). [onDestroyed] advances the
 * generation (invalidating any in-flight retired callbacks) and stops the
 * current session once.
 *
 * Single-threaded: all methods run on the AccessibilityService main thread.
 */
class ShortcutServiceLifecycle(
    private val onEvent: (ShortcutSnapEvent) -> Unit,
    private val sessionFactory: (sink: (ShortcutSnapEvent) -> Unit) -> ShortcutSession,
) {

    private var current: ShortcutSession? = null
    private var generation = 0L

    /** Retires any prior session and installs a fresh one. Safe to call repeatedly. */
    fun onConnected() {
        // Install the new identity FIRST so a retired session's late callback
        // (even one triggered by stopping it) is already stale.
        val gen = ++generation
        current?.stop()
        val session = sessionFactory { event -> if (gen == generation) onEvent(event) }
        current = session
        session.start()
    }

    /** Routes a key event to the current session (null decision when none). */
    fun handleKey(
        deviceId: Int,
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        altPressed: Boolean,
        metaPressed: Boolean,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
    ): ShortcutKeyDecision? =
        current?.handleKey(
            deviceId = deviceId,
            action = action,
            keyCode = keyCode,
            repeatCount = repeatCount,
            altPressed = altPressed,
            metaPressed = metaPressed,
            ctrlPressed = ctrlPressed,
            shiftPressed = shiftPressed,
        )

    /**
     * Clears transient consumed key-stream ownership on the current session, for
     * AccessibilityService interruption. Idempotent and harmless when there is no
     * current session; it does NOT stop the backend, retire the session, or
     * change the lifecycle generation.
     */
    fun resetInputOwnership() {
        current?.resetInputOwnership()
    }

    /** Stops the current session once and invalidates retired callbacks. Idempotent. */
    fun onDestroyed() {
        ++generation
        current?.stop()
        current = null
    }
}

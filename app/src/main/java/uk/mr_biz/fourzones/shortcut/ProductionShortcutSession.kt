package uk.mr_biz.fourzones.shortcut

import android.content.Context

/**
 * The production [ShortcutSession]: its own [ShortcutSnapComposition] (backend +
 * orchestrator) plus a [ShortcutSnapDispatcher]. Created per AccessibilityService
 * connection by [ShortcutServiceLifecycle], which guarantees at most one is live
 * and retires it on reconnect/destroy.
 */
class ProductionShortcutSession(
    context: Context,
    selfPackageName: String,
    sink: (ShortcutSnapEvent) -> Unit,
) : ShortcutSession {

    private val composition = ShortcutSnapComposition(context, selfPackageName, sink)
    private val dispatcher = ShortcutSnapDispatcher { quadrant -> composition.requestSnap(quadrant) }

    // Consumption is layered ON TOP of the unchanged match→map→submit dispatcher:
    // the tracker submits (via the dispatcher) only on a matched initial DOWN and
    // owns the digit stream so its repeats/UP are consumed too.
    private val tracker = ShortcutKeyConsumptionTracker { action, keyCode, repeat, alt, meta, ctrl, shift ->
        dispatcher.handle(
            action = action,
            keyCode = keyCode,
            repeatCount = repeat,
            altPressed = alt,
            metaPressed = meta,
            ctrlPressed = ctrl,
            shiftPressed = shift,
        )
    }

    override fun start() = composition.start()

    override fun stop() {
        composition.stop()
        tracker.reset() // forget any owned streams on lifecycle teardown
    }

    override fun resetInputOwnership() {
        // Transient input-state cleanup only — the backend/composition stays up.
        tracker.reset()
    }

    override fun handleKey(
        deviceId: Int,
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        altPressed: Boolean,
        metaPressed: Boolean,
        ctrlPressed: Boolean,
        shiftPressed: Boolean,
    ): ShortcutKeyDecision =
        tracker.onKeyEvent(
            deviceId = deviceId,
            action = action,
            keyCode = keyCode,
            repeatCount = repeatCount,
            altPressed = altPressed,
            metaPressed = metaPressed,
            ctrlPressed = ctrlPressed,
            shiftPressed = shiftPressed,
        )
}

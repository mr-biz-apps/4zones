package uk.mr_biz.fourzones.display

import android.content.Context
import android.os.Looper

/**
 * Display-discovery component: coordinates listener registration and publishes
 * immutable [DisplayInfo] snapshots through [onDisplaysChanged].
 *
 * Threading/lifecycle contract:
 *  - [start] and [stop] MUST be called on the main thread (enforced by check),
 *    and both are idempotent.
 *  - Inside [start], listener registration happens first and the initial
 *    snapshot is published synchronously right after, all on the main thread —
 *    one ordered lifecycle path, so no change event can be lost in between
 *    (an event racing the initial read simply publishes again).
 *  - Subsequent change events are delivered on the main thread by the source.
 *
 * Only the application context is retained (see [FrameworkDisplaySource]), so
 * no Activity or other UI context can leak.
 */
class DisplayDiscovery internal constructor(
    private val source: DisplaySource,
    private val isMainThread: () -> Boolean,
    private val onDisplaysChanged: (List<DisplayInfo>) -> Unit,
) {

    constructor(
        context: Context,
        onDisplaysChanged: (List<DisplayInfo>) -> Unit,
    ) : this(
        source = FrameworkDisplaySource(context.applicationContext),
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        onDisplaysChanged = onDisplaysChanged,
    )

    private var started = false

    fun start() {
        requireMainThread("start")
        if (started) return
        started = true
        source.registerChangeListener { publishSnapshot() }
        publishSnapshot()
    }

    fun stop() {
        requireMainThread("stop")
        if (!started) return
        started = false
        source.unregisterChangeListener()
    }

    /** Reads and classifies all currently attached displays. */
    fun snapshot(): List<DisplayInfo> = source.readDisplays()
        .map { properties -> DisplayInfo(properties, DisplayClassifier.classify(properties)) }

    private fun publishSnapshot() = onDisplaysChanged(snapshot())

    private fun requireMainThread(method: String) {
        check(isMainThread()) { "DisplayDiscovery.$method() must be called on the main thread" }
    }
}

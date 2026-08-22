package uk.mr_biz.fourzones.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper

/**
 * Smallest injectable boundary between [DisplayDiscovery] coordination logic
 * and the Android framework, so registration/lifecycle behavior is
 * JVM-testable without framework scaffolding.
 */
interface DisplaySource {

    /**
     * Begin delivering change notifications. The framework implementation
     * funnels display added/removed/changed events into [onChanged] on the
     * main thread. Idempotent while already registered.
     */
    fun registerChangeListener(onChanged: () -> Unit)

    /** Stop delivering change notifications. Harmless when not registered. */
    fun unregisterChangeListener()

    /** Read an immutable snapshot of all currently attached displays. */
    fun readDisplays(): List<DisplayProperties>
}

/** Production [DisplaySource] backed by [DisplayManager]. */
class FrameworkDisplaySource(context: Context) : DisplaySource {

    private val appContext = context.applicationContext
    private val displayManager =
        requireNotNull(appContext.getSystemService(DisplayManager::class.java)) {
            "DisplayManager service unavailable"
        }
    private val reader = DisplayPropertiesReader(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: DisplayManager.DisplayListener? = null

    override fun registerChangeListener(onChanged: () -> Unit) {
        if (listener != null) return
        val newListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChanged()
            override fun onDisplayRemoved(displayId: Int) = onChanged()
            override fun onDisplayChanged(displayId: Int) = onChanged()
        }
        listener = newListener
        displayManager.registerDisplayListener(newListener, mainHandler)
    }

    override fun unregisterChangeListener() {
        listener?.let(displayManager::unregisterDisplayListener)
        listener = null
    }

    override fun readDisplays(): List<DisplayProperties> {
        val presentationCategoryIds = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()
        return displayManager.displays
            .filter { it.isValid }
            .mapNotNull { display ->
                // A display can be hot-unplugged between enumeration and read;
                // skip the unreadable display rather than fail the snapshot.
                // The next remove event triggers a fresh, consistent snapshot.
                runCatching { reader.read(display, presentationCategoryIds) }.getOrNull()
            }
    }
}

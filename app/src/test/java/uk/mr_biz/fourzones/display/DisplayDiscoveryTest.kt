package uk.mr_biz.fourzones.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle/coordination tests against the injectable [DisplaySource] seam.
 *
 * Framework-side behavior (the DisplayManager listener fan-in of
 * added/removed/changed and the hot-unplug runCatching skip in
 * FrameworkDisplaySource.readDisplays) cannot reasonably be JVM-tested and is
 * intentionally not simulated here; those paths are thin and covered by the
 * conservative skip-and-republish design.
 */
class DisplayDiscoveryTest {

    private class FakeDisplaySource : DisplaySource {
        var registerCount = 0
        var unregisterCount = 0
        var displays: List<DisplayProperties> = emptyList()
        private var onChanged: (() -> Unit)? = null

        val isRegistered get() = onChanged != null

        override fun registerChangeListener(onChanged: () -> Unit) {
            registerCount++
            this.onChanged = onChanged
        }

        override fun unregisterChangeListener() {
            unregisterCount++
            onChanged = null
        }

        override fun readDisplays(): List<DisplayProperties> = displays

        fun fireChange() {
            checkNotNull(onChanged) { "change fired with no registered listener" }.invoke()
        }
    }

    private fun testProperties(displayId: Int, name: String = "D$displayId") = DisplayProperties(
        displayId = displayId,
        name = name,
        physicalWidthPx = 1920,
        physicalHeightPx = 1080,
        rotation = DisplayRotation.DEG_0,
        refreshRateHz = 60f,
        densityDpi = 320,
        flags = emptySet(),
        isDefaultDisplay = displayId == 0,
        inPresentationCategory = false,
        isInternal = null,
    )

    private fun discovery(
        source: FakeDisplaySource,
        onMainThread: Boolean = true,
        emissions: MutableList<List<DisplayInfo>> = mutableListOf(),
    ) = Pair(
        DisplayDiscovery(
            source = source,
            isMainThread = { onMainThread },
            onDisplaysChanged = { emissions.add(it) },
        ),
        emissions,
    )

    @Test
    fun `start registers and publishes the initial snapshot`() {
        val source = FakeDisplaySource().apply { displays = listOf(testProperties(0)) }
        val (discovery, emissions) = discovery(source)

        discovery.start()

        assertEquals(1, source.registerCount)
        assertEquals(1, emissions.size)
        assertEquals(1, emissions[0].size)
        assertEquals(0, emissions[0][0].properties.displayId)
        // Snapshots arrive classified.
        assertEquals(
            DisplayClassifier.classify(source.displays[0]),
            emissions[0][0].classification,
        )
    }

    @Test
    fun `duplicate start does not duplicate registration or publication`() {
        val source = FakeDisplaySource()
        val (discovery, emissions) = discovery(source)

        discovery.start()
        discovery.start()

        assertEquals(1, source.registerCount)
        assertEquals(1, emissions.size)
    }

    @Test
    fun `duplicate stop is harmless and stop without start does nothing`() {
        val source = FakeDisplaySource()
        val (discovery, _) = discovery(source)

        discovery.stop()
        assertEquals(0, source.unregisterCount)

        discovery.start()
        discovery.stop()
        discovery.stop()
        assertEquals(1, source.unregisterCount)
    }

    @Test
    fun `registration and unregistration stay balanced across lifecycle cycles`() {
        val source = FakeDisplaySource()
        val (discovery, _) = discovery(source)

        repeat(3) {
            discovery.start()
            discovery.stop()
        }

        assertEquals(3, source.registerCount)
        assertEquals(3, source.unregisterCount)
        assertEquals(false, source.isRegistered)
    }

    @Test
    fun `display change event triggers a fresh classified snapshot`() {
        val source = FakeDisplaySource().apply { displays = listOf(testProperties(0)) }
        val (discovery, emissions) = discovery(source)
        discovery.start()

        // Simulate add, change, remove: each event re-reads the source.
        source.displays = listOf(testProperties(0), testProperties(42))
        source.fireChange()
        source.displays = listOf(
            testProperties(0),
            testProperties(42).copy(flags = setOf(DisplayFlag.PRESENTATION)),
        )
        source.fireChange()
        source.displays = listOf(testProperties(0))
        source.fireChange()

        assertEquals(4, emissions.size)
        assertEquals(listOf(0, 42), emissions[1].map { it.properties.displayId })
        assertTrue(emissions[2][1].classification.isPresentationCapable)
        assertEquals(listOf(0), emissions[3].map { it.properties.displayId })
    }

    @Test
    fun `start and stop enforce the main-thread contract`() {
        val source = FakeDisplaySource()
        val (discovery, emissions) = discovery(source, onMainThread = false)

        assertThrows(IllegalStateException::class.java) { discovery.start() }
        assertThrows(IllegalStateException::class.java) { discovery.stop() }
        assertEquals(0, source.registerCount)
        assertEquals(0, emissions.size)
    }
}

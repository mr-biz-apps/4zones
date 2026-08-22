package uk.mr_biz.fourzones.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayClassifierTest {

    private fun properties(
        displayId: Int = 0,
        name: String = "Test display",
        physicalWidthPx: Int = 1080,
        physicalHeightPx: Int = 2340,
        rotation: DisplayRotation = DisplayRotation.DEG_0,
        refreshRateHz: Float = 60f,
        densityDpi: Int = 420,
        flags: Set<DisplayFlag> = emptySet(),
        isDefaultDisplay: Boolean = false,
        inPresentationCategory: Boolean = false,
        isInternal: Boolean? = null,
    ) = DisplayProperties(
        displayId = displayId,
        name = name,
        physicalWidthPx = physicalWidthPx,
        physicalHeightPx = physicalHeightPx,
        rotation = rotation,
        refreshRateHz = refreshRateHz,
        densityDpi = densityDpi,
        flags = flags,
        isDefaultDisplay = isDefaultDisplay,
        inPresentationCategory = inPresentationCategory,
        isInternal = isInternal,
    )

    private fun defaultDisplayPre361() = properties(
        displayId = 0,
        isDefaultDisplay = true,
        isInternal = null,
    )

    private fun presentationSecondary(displayId: Int = 2, isInternal: Boolean? = null) = properties(
        displayId = displayId,
        physicalWidthPx = 1920,
        physicalHeightPx = 1080,
        flags = setOf(DisplayFlag.PRESENTATION),
        inPresentationCategory = true,
        isInternal = isInternal,
    )

    @Test
    fun `pre-361 default display has undetermined origin and desktop capability`() {
        val c = DisplayClassifier.classify(defaultDisplayPre361())

        // Origin is honestly unknown without isInternal(), not forced to a Boolean.
        assertEquals(DisplayOrigin.UNDETERMINED, c.origin)
        assertFalse(c.isPresentationCapable)
        assertEquals(DesktopCapability.UNDETERMINED, c.desktopCapability)
        assertTrue(c.evidence.any { it.contains("unavailable on this API level") })
    }

    @Test
    fun `internal display is built-in but desktop capability is not falsely inferred`() {
        val c = DisplayClassifier.classify(
            properties(displayId = 0, isDefaultDisplay = true, isInternal = true),
        )

        assertEquals(DisplayOrigin.BUILT_IN, c.origin)
        assertEquals(DesktopCapability.UNDETERMINED, c.desktopCapability)
    }

    @Test
    fun `built-in display is not categorically rejected as a future desktop workspace`() {
        val direct = DisplayClassifier.classify(properties(isDefaultDisplay = true, isInternal = true))
        val fallback = DisplayClassifier.classify(defaultDisplayPre361())

        assertNotEquals(DesktopCapability.NOT_A_CANDIDATE, direct.desktopCapability)
        assertNotEquals(DesktopCapability.NOT_A_CANDIDATE, fallback.desktopCapability)
        assertTrue(direct.evidence.any { it.contains("not ruled out") })
    }

    @Test
    fun `presentation capability does not establish origin`() {
        // Android 16 case: built-in displays may be presentation-capable, so
        // presentation evidence must never flip origin away from what
        // isInternal() says (or from UNDETERMINED when it is unavailable).
        val internalPresentation = DisplayClassifier.classify(
            presentationSecondary(isInternal = true),
        )
        val unknownPresentation = DisplayClassifier.classify(
            presentationSecondary(isInternal = null),
        )

        assertTrue(internalPresentation.isPresentationCapable)
        assertEquals(DisplayOrigin.BUILT_IN, internalPresentation.origin)
        assertEquals(DesktopCapability.UNDETERMINED, internalPresentation.desktopCapability)

        assertTrue(unknownPresentation.isPresentationCapable)
        assertEquals(DisplayOrigin.UNDETERMINED, unknownPresentation.origin)
        assertTrue(unknownPresentation.evidence.any { it.contains("does not establish origin") })
    }

    @Test
    fun `non-internal presentation display is a desktop candidate`() {
        val c = DisplayClassifier.classify(presentationSecondary(isInternal = false))

        assertEquals(DisplayOrigin.NON_INTERNAL, c.origin)
        assertTrue(c.isPresentationCapable)
        assertEquals(DesktopCapability.CANDIDATE, c.desktopCapability)
    }

    @Test
    fun `pre-361 presentation secondary display remains a desktop candidate despite unknown origin`() {
        val c = DisplayClassifier.classify(presentationSecondary(isInternal = null))

        assertEquals(DisplayOrigin.UNDETERMINED, c.origin)
        assertEquals(DesktopCapability.CANDIDATE, c.desktopCapability)
        assertTrue(c.evidence.any { it.contains("not proof of desktop mode") })
    }

    @Test
    fun `private presentation display stays presentation-capable but is not a candidate`() {
        val c = DisplayClassifier.classify(
            properties(
                displayId = 4,
                flags = setOf(DisplayFlag.PRESENTATION, DisplayFlag.PRIVATE),
            ),
        )

        // Privacy limits usability; it does not erase presentation capability.
        assertTrue(c.isPresentationCapable)
        assertTrue(c.isPrivate)
        assertEquals(DesktopCapability.NOT_A_CANDIDATE, c.desktopCapability)
    }

    @Test
    fun `secondary display without presentation capability is not presentation-capable`() {
        val c = DisplayClassifier.classify(
            properties(displayId = 5, flags = emptySet(), inPresentationCategory = false),
        )

        assertFalse(c.isPresentationCapable)
        assertEquals(DisplayOrigin.UNDETERMINED, c.origin)
        // Not auto-promoted to desktop candidate, but not disproven either.
        assertEquals(DesktopCapability.UNDETERMINED, c.desktopCapability)
    }

    @Test
    fun `display with no usable size is not a desktop candidate`() {
        val c = DisplayClassifier.classify(
            presentationSecondary().copy(physicalWidthPx = 0, physicalHeightPx = 0),
        )

        assertEquals(DesktopCapability.NOT_A_CANDIDATE, c.desktopCapability)
    }

    @Test
    fun `multiple displays are classified independently`() {
        val infos = listOf(
            defaultDisplayPre361(),
            presentationSecondary(displayId = 2),
            properties(displayId = 3),
        ).map { DisplayClassifier.classify(it) }

        assertEquals(DesktopCapability.UNDETERMINED, infos[0].desktopCapability)
        assertEquals(DesktopCapability.CANDIDATE, infos[1].desktopCapability)
        assertFalse(infos[2].isPresentationCapable)
        assertEquals(DesktopCapability.UNDETERMINED, infos[2].desktopCapability)
    }

    @Test
    fun `changed properties produce a new snapshot and a new classification`() {
        val before = properties(displayId = 2, flags = emptySet())
        val after = before.copy(flags = setOf(DisplayFlag.PRESENTATION))

        val classifiedBefore = DisplayClassifier.classify(before)
        val classifiedAfter = DisplayClassifier.classify(after)

        // The original snapshot is untouched (immutability) and re-classifying
        // the updated snapshot changes the verdict.
        assertEquals(emptySet<DisplayFlag>(), before.flags)
        assertNotEquals(before, after)
        assertEquals(DesktopCapability.UNDETERMINED, classifiedBefore.desktopCapability)
        assertEquals(DesktopCapability.CANDIDATE, classifiedAfter.desktopCapability)
    }

    @Test
    fun `classification ignores display id values entirely`() {
        // Unusual, non-sequential IDs: identical capabilities must classify
        // identically regardless of ID, proving no numeric-ID assumption.
        val ids = listOf(0, 7, 12, 4711, Int.MAX_VALUE)
        val classifications =
            ids.map { DisplayClassifier.classify(presentationSecondary(displayId = it)) }

        classifications.forEach { c ->
            assertEquals(classifications.first(), c)
            assertEquals(DesktopCapability.CANDIDATE, c.desktopCapability)
        }

        // A default display keeps its classification even with an unusual ID,
        // because decisions use capability bits, never the numeric ID.
        val weirdDefault = DisplayClassifier.classify(
            properties(displayId = 9999, isDefaultDisplay = true, isInternal = true),
        )
        assertEquals(DisplayOrigin.BUILT_IN, weirdDefault.origin)
        assertEquals(DesktopCapability.UNDETERMINED, weirdDefault.desktopCapability)
    }

    @Test
    fun `evidence explains the desktop capability verdict`() {
        val candidate = DisplayClassifier.classify(presentationSecondary(isInternal = false))
        val builtIn = DisplayClassifier.classify(properties(isDefaultDisplay = true, isInternal = true))
        val bareSecondary = DisplayClassifier.classify(properties(displayId = 3))
        val privateDisplay = DisplayClassifier.classify(
            properties(flags = setOf(DisplayFlag.PRESENTATION, DisplayFlag.PRIVATE)),
        )

        assertTrue(candidate.evidence.any { it.contains("Desktop candidate") })
        assertTrue(builtIn.evidence.any { it.contains("undetermined") })
        assertTrue(bareSecondary.evidence.any { it.contains("not assumed desktop-capable") })
        assertTrue(privateDisplay.evidence.any { it.contains("regardless of its presentation capability") })
    }
}

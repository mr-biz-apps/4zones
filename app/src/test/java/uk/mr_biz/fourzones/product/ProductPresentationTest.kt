package uk.mr_biz.fourzones.product

import uk.mr_biz.fourzones.geometry.Quadrant
import uk.mr_biz.fourzones.privileged.PrivilegedBackendStatus
import uk.mr_biz.fourzones.shortcut.QuadrantShortcutMapping
import uk.mr_biz.fourzones.shortcut.ShortcutCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductPresentationTest {

    @Test
    fun `shortcut grid maps each digit to its zone and position exactly`() {
        assertEquals(
            listOf(
                ShortcutZoneReference(1, "Zone 1", "Top left", "Alt + Win + 1", Quadrant.TOP_LEFT),
                ShortcutZoneReference(2, "Zone 2", "Top right", "Alt + Win + 2", Quadrant.TOP_RIGHT),
                ShortcutZoneReference(
                    3,
                    "Zone 3",
                    "Bottom left",
                    "Alt + Win + 3",
                    Quadrant.BOTTOM_LEFT,
                ),
                ShortcutZoneReference(
                    4,
                    "Zone 4",
                    "Bottom right",
                    "Alt + Win + 4",
                    Quadrant.BOTTOM_RIGHT,
                ),
            ),
            shortcutZoneReference,
        )
    }

    @Test
    fun `tapped zone and pressed chord resolve to the SAME quadrant`() {
        // The tap path must never grow a parallel digit-to-quadrant table: the
        // card's own quadrant is checked against the keyboard path's mapping.
        val byDigit = ShortcutCandidate.entries.associateBy { it.ordinal + 1 }
        assertEquals(shortcutZoneReference.size, byDigit.size)
        shortcutZoneReference.forEach { zone ->
            val candidate = byDigit.getValue(zone.digit)
            assertEquals(
                "zone ${zone.digit} must snap where its chord snaps",
                QuadrantShortcutMapping.toQuadrant(candidate),
                zone.quadrant,
            )
        }
        // And every quadrant is reachable by tap — no zone is a duplicate.
        assertEquals(
            Quadrant.entries.toSet(),
            shortcutZoneReference.map { it.quadrant }.toSet(),
        )
    }

    @Test
    fun `each card is announced by what tapping it does, not by its heading`() {
        assertEquals(
            listOf(
                "Snap window to top left",
                "Snap window to top right",
                "Snap window to bottom left",
                "Snap window to bottom right",
            ),
            shortcutZoneReference.map { it.snapContentDescription },
        )
        shortcutZoneReference.forEach { zone ->
            assertFalse(
                "the spoken label must not be the bare zone heading",
                zone.snapContentDescription == zone.zoneLabel,
            )
        }
    }

    @Test
    fun `screen copy states what the product does and how the delay works`() {
        assertEquals("Snap windows to screen zones on Samsung DeX.", ProductCopy.SUBTITLE)
        assertEquals("Zones", ProductCopy.ZONES_HEADING)
        assertEquals(
            "Tap a zone. The window you're using moves a few seconds later — " +
                "bring it to the front if it isn't already.",
            ProductCopy.TAP_INSTRUCTION,
        )
    }

    @Test
    fun `READY reads keyboard shortcuts ready and shows no setup card`() {
        assertEquals("Keyboard shortcuts ready", productBanner(ProductReadiness.READY))
        assertNull(productSetupContent(ProductReadiness.READY))
    }

    @Test
    fun `service disabled offers the enable-keyboard-shortcuts action`() {
        val content = productSetupContent(ProductReadiness.SHORTCUT_SERVICE_DISABLED)
        assertNotNull(content)
        assertEquals(SetupActionKind.OPEN_ACCESSIBILITY_SETTINGS, content?.actionKind)
        assertEquals("Enable keyboard shortcuts", content?.actionLabel)
        assertEquals("Prefer the keyboard?", content?.headline)
        assertEquals(
            "Enable the 4Zones shortcut service to use Alt + Win + 1–4 " +
                "instead of tapping a zone.",
            content?.body,
        )
        // No longer "Setup required": with tap-to-snap the app works without the
        // service, so the banner names the missing OPTION, not a blocked product.
        assertEquals(
            "Keyboard shortcuts are off",
            productBanner(ProductReadiness.SHORTCUT_SERVICE_DISABLED),
        )
        // The banner states the fact; the card offers the alternative. They must
        // not say the same thing twice on one screen.
        assertNotEquals(
            productBanner(ProductReadiness.SHORTCUT_SERVICE_DISABLED),
            content?.headline,
        )
    }

    @Test
    fun `permission required offers the permission action`() {
        val content = productSetupContent(ProductReadiness.SHIZUKU_PERMISSION_REQUIRED)
        assertNotNull(content)
        assertEquals(SetupActionKind.REQUEST_SHIZUKU_PERMISSION, content?.actionKind)
        assertEquals("Allow permission", content?.actionLabel)
    }

    @Test
    fun `connecting shows starting with no recovery card or button`() {
        assertEquals("Starting…", productBanner(ProductReadiness.CONNECTING))
        assertNull(productSetupContent(ProductReadiness.CONNECTING))
    }

    @Test
    fun `guidance-only states have no action button`() {
        listOf(
            ProductReadiness.SHIZUKU_NOT_INSTALLED,
            ProductReadiness.SHIZUKU_UNAVAILABLE,
            ProductReadiness.UNSUPPORTED_SHIZUKU,
            ProductReadiness.RESTART_REQUIRED,
        ).forEach { readiness ->
            val content = productSetupContent(readiness)
            assertNotNull("$readiness must have a setup card", content)
            assertNull("$readiness has no safe action in this slice", content?.actionKind)
            assertNull("$readiness has no safe action in this slice", content?.actionLabel)
        }
    }

    @Test
    fun `every readiness value has deliberate presentation`() {
        ProductReadiness.entries.forEach { readiness ->
            assertTrue(
                "$readiness must have a non-blank banner",
                productBanner(readiness).isNotBlank(),
            )
            val content = productSetupContent(readiness)
            when (readiness) {
                // No card: READY needs nothing; CONNECTING is brief/self-resolving.
                ProductReadiness.READY, ProductReadiness.CONNECTING ->
                    assertNull("$readiness must not show a setup card", content)
                else -> {
                    assertNotNull("$readiness must show a setup card", content)
                    assertTrue(content!!.headline.isNotBlank())
                    assertTrue(content.body.isNotBlank())
                    // Action kind and label are both present or both absent.
                    assertEquals(content.actionKind == null, content.actionLabel == null)
                }
            }
        }
    }

    @Test
    fun `no product wording leaks raw engineering state or banned phrases`() {
        val allStrings = buildList {
            ProductReadiness.entries.forEach { readiness ->
                add(productBanner(readiness))
                productSetupContent(readiness)?.let {
                    add(it.headline)
                    add(it.body)
                    it.actionLabel?.let(::add)
                }
            }
            shortcutZoneReference.forEach {
                add(it.zoneLabel)
                add(it.positionLabel)
                add(it.chordLabel)
                add(it.snapContentDescription)
            }
            add(ProductCopy.SUBTITLE)
            add(ProductCopy.ZONES_HEADING)
            add(ProductCopy.TAP_INSTRUCTION)
            // Every disabled-reason line is product copy too.
            (listOf(null) + PrivilegedBackendStatus.entries).forEach { status ->
                snapDisabledReason(status)?.let(::add)
            }
        }
        allStrings.forEach { text ->
            assertFalse("banned wording in \"$text\"", text.contains("Ready to snap"))
            // Android Settings lists the service as "4Zones keyboard shortcuts",
            // so the product says "shortcuts" and never "bindings".
            assertFalse("banned wording in \"$text\"", text.contains("binding", ignoreCase = true))
            assertFalse("raw enum leak in \"$text\"", text.contains("BINDER"))
            assertFalse("raw enum leak in \"$text\"", text.contains("PrivilegedBackendStatus"))
            assertFalse("raw enum leak in \"$text\"", text.contains("USER_SERVICE"))
        }
    }
}

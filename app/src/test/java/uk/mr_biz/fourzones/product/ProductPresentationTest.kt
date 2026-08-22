package uk.mr_biz.fourzones.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductPresentationTest {

    @Test
    fun `shortcut grid maps each digit to its zone and position exactly`() {
        assertEquals(
            listOf(
                ShortcutZoneReference(1, "Zone 1", "Top left", "Alt + Win + 1"),
                ShortcutZoneReference(2, "Zone 2", "Top right", "Alt + Win + 2"),
                ShortcutZoneReference(3, "Zone 3", "Bottom left", "Alt + Win + 3"),
                ShortcutZoneReference(4, "Zone 4", "Bottom right", "Alt + Win + 4"),
            ),
            shortcutZoneReference,
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
        assertEquals("Setup required", productBanner(ProductReadiness.SHORTCUT_SERVICE_DISABLED))
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
            }
        }
        allStrings.forEach { text ->
            assertFalse("banned wording in \"$text\"", text.contains("Ready to snap"))
            assertFalse("raw enum leak in \"$text\"", text.contains("BINDER"))
            assertFalse("raw enum leak in \"$text\"", text.contains("PrivilegedBackendStatus"))
            assertFalse("raw enum leak in \"$text\"", text.contains("USER_SERVICE"))
        }
    }
}

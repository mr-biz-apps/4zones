package uk.mr_biz.fourzones

import uk.mr_biz.fourzones.product.ProductReadiness
import uk.mr_biz.fourzones.product.productSetupContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Protects the RELEASE-FACING public identity introduced in 4B5, without a
 * brittle full-resource snapshot: the app name and Accessibility service label
 * are "4Zones", stale "DexZones window shortcuts"/"Dex Zones" wording is gone,
 * and the user-facing Shizuku setup text carries no internal privilege jargon.
 *
 * Internal identifiers (package, log tags, theme/channel ids, class names) are
 * deliberately NOT asserted here — they may remain DexZones.
 */
class PublicNamingTest {

    private fun stringResource(name: String): String {
        // Unit-test working directory is the :app module directory.
        val xml = File("src/main/res/values/strings.xml").readText()
        val match = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
        return requireNotNull(match) { "string $name not found" }.groupValues[1]
    }

    @Test
    fun `public app name is 4Zones`() {
        assertEquals("4Zones", stringResource("app_name"))
    }

    @Test
    fun `accessibility service label is 4Zones keyboard shortcuts`() {
        assertEquals("4Zones keyboard shortcuts", stringResource("shortcut_service_label"))
    }

    @Test
    fun `no stale public DexZones or Dex Zones wording remains in string resources`() {
        val xml = File("src/main/res/values/strings.xml").readText()
        assertFalse(xml.contains("DexZones window shortcuts"))
        assertFalse(xml.contains("Dex Zones"))
    }

    @Test
    fun `accessibility description stays narrow and makes no forbidden claims`() {
        val description = stringResource("shortcut_service_description").lowercase()
        // Narrowly about the shortcut chords; not a disability/screen-reading tool.
        assertTrue(description.contains("keyboard shortcuts"))
        assertFalse(description.contains("screen reader"))
        assertFalse(description.contains("accessibility assistance"))
        // Truthful privacy posture preserved.
        assertTrue(description.contains("does not read screen content"))
        assertTrue(description.contains("does not record your typing"))
    }

    @Test
    fun `user-facing Shizuku setup text contains no internal privilege jargon`() {
        val jargon = listOf("binder", "shell uid", "manage_activity_tasks", "dumpsys")
        ProductReadiness.entries.forEach { readiness ->
            val content = productSetupContent(readiness) ?: return@forEach
            val text = (content.headline + " " + content.body).lowercase()
            jargon.forEach { term ->
                assertFalse("\"$term\" leaked into $readiness setup text", text.contains(term))
            }
            assertFalse("banned wording in $readiness", text.contains("ready to snap"))
        }
    }
}

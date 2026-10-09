package com.lumenpearson.lessons.appicon

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The catalog, the manifest's aliases and res/ name the same icons.
 *
 * Taking a variant out is three deletions in three places, and the owner is
 * going to take most of them out (docs/specs/2026-10-09-app-icons-design.md,
 * «Taking variants out»). A forgotten alias is a launcher entry the switch
 * never turns off; a forgotten catalog line is a tile whose alias does not
 * exist, which is an exception on «Применить»; forgotten files are dead weight
 * in every APK. Each of those fails here, by name.
 */
class AppIconCatalogTest {

    private val manifest: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement
    private val application = manifest.descendants("application").single()
    private val aliases = application.descendants("activity-alias")
    private val res = File("src/main/res")

    @Test
    fun `every catalog entry has an alias and every alias an entry, in catalog order`() {
        assertEquals(
            AppIconCatalog.variants.map { ".launcher.${it.key}" },
            aliases.map { it.getAttribute("android:name") },
        )
    }

    @Test
    fun `each alias opens MainActivity from the launcher under its own icon`() {
        for ((alias, variant) in aliases.zip(AppIconCatalog.variants)) {
            val name = variant.resourceName
            assertEquals(variant.key, ".MainActivity", alias.getAttribute("android:targetActivity"))
            assertEquals(variant.key, "true", alias.getAttribute("android:exported"))
            assertEquals(variant.key, "@mipmap/$name", alias.getAttribute("android:icon"))
            assertEquals(variant.key, "@mipmap/${name}_round", alias.getAttribute("android:roundIcon"))
            assertTrue("${variant.key} is not a launcher entry", alias.isLauncherEntry())
        }
    }

    @Test
    fun `only the default is enabled before any code runs`() {
        val enabled = aliases.zip(AppIconCatalog.variants)
            .filter { (alias, _) -> alias.getAttribute("android:enabled") == "true" }
            .map { (_, variant) -> variant }
        assertEquals(listOf(AppIconCatalog.default), enabled)
        // Said outright on every alias: one with no android:enabled is enabled,
        // and a fresh install would show two icons.
        assertTrue(aliases.all { it.getAttribute("android:enabled") in setOf("true", "false") })
    }

    @Test
    fun `MainActivity is never a launcher entry and never disabled`() {
        val main = application.descendants("activity")
            .single { it.getAttribute("android:name") == ".MainActivity" }
        assertFalse(main.isLauncherEntry())
        assertNotEquals("false", main.getAttribute("android:enabled"))
    }

    @Test
    fun `the application's own icon is the default`() {
        val name = AppIconCatalog.default.resourceName
        assertEquals("@mipmap/$name", application.getAttribute("android:icon"))
        assertEquals("@mipmap/${name}_round", application.getAttribute("android:roundIcon"))
    }

    @Test
    fun `each entry's adaptive icons point at its own layers`() {
        for (variant in AppIconCatalog.variants) {
            val name = variant.resourceName
            for (file in listOf("$name.xml", "${name}_round.xml")) {
                val icon = File(res, "mipmap-anydpi-v26/$file")
                assertTrue("missing ${icon.path}", icon.isFile)
                val text = icon.readText()
                assertTrue("$file: not its own background", "@drawable/${name}_background\"" in text)
                assertTrue("$file: not its own foreground", "@drawable/${name}_foreground\"" in text)
                assertTrue("$file: no shared monochrome layer", MonochromeLayers.any { "@drawable/$it\"" in text })
            }
            for (layer in listOf("background", "foreground")) {
                assertTrue("no $layer layer for ${variant.key}", layerExists("${name}_$layer"))
            }
        }
    }

    @Test
    fun `no launcher resource is left that no catalog entry uses`() {
        val own = AppIconCatalog.variants.flatMap { variant ->
            val name = variant.resourceName
            listOf(name, "${name}_round", "${name}_background", "${name}_foreground")
        }
        // The shared layers count only while an icon still points at one. Keep
        // «Классика» alone and every icon on the edge-to-edge layer has gone, and
        // a fixed list of the two would leave it in every APK unremarked.
        val monochrome = AppIconCatalog.variants
            .flatMap { listOf("${it.resourceName}.xml", "${it.resourceName}_round.xml") }
            .map { File(res, "mipmap-anydpi-v26/$it") }
            .filter { it.isFile }
            .flatMap { file -> MonochromeReference.findAll(file.readText()).map { it.groupValues[1] }.toList() }
        val used = (own + monochrome).toSet()
        val present = res.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().toList() }
            .map { it.nameWithoutExtension }
            .filter { it.startsWith("ic_launcher") }
            .toSet()

        assertEquals("launcher resources no icon uses", emptySet<String>(), present - used)
        assertFalse(
            "the old book's colour is still declared",
            "ic_launcher_background" in File(res, "values/colors.xml").readText(),
        )
    }

    private fun layerExists(stem: String): Boolean =
        listOf("drawable/$stem.xml", "drawable-xxxhdpi/$stem.webp").any { File(res, it).isFile }

    private companion object {
        /** The two monochrome layers every icon shares: the classic dial, and the dial edge to edge. */
        val MonochromeLayers = listOf("ic_launcher_monochrome_classic", "ic_launcher_monochrome_edge")

        /** A mipmap's pointer at a shared monochrome layer, whichever of them. */
        val MonochromeReference = Regex("""@drawable/(ic_launcher_monochrome_\w+)""")

        fun Element.descendants(tag: String): List<Element> =
            getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

        fun Element.isLauncherEntry(): Boolean = descendants("category")
            .any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }
    }
}

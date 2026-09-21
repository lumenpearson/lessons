package com.lumenpearson.lessons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * A string nothing draws is a claim nobody checked.
 *
 * A dead `<string>` is not inert here, for three reasons that each cost
 * something. It is translated, so the English half is maintained for a
 * sentence nobody will read. It shows up in correction mode's registry as a
 * key, so a proofreader can be handed a line the app has no screen for. And it
 * outlives the screen it was written for, which is how «Уведомления
 * заблокированы» sat in `values/` describing a settings group that had been
 * rebuilt around the system's own permission sheet — the words still read
 * plausibly, and nothing at all said they were unreachable.
 *
 * Ten of them had accumulated, three of which were the earlier spelling of a
 * string that had since been split in two. Shrinking is the point: this is a
 * ratchet, not a tolerance, and the way to satisfy it is to delete the string
 * or to draw it.
 *
 * Read out of the source tree, like `ResourceTranslationTest` beside it and for
 * the same reason: by the time resources are compiled, «nothing references
 * this» has become an id like any other. AGP's own lint would say it too, but
 * `./gradlew lint` is not a CI gate here and `./gradlew test` is.
 */
class UnusedStringTest {

    private val modules = listOf("app", "core/data", "core/designsystem", "widget")

    /** `R.string.x`, `R.plurals.x`, and the `@string/x` an XML file writes. */
    private val fromKotlin = Regex("""R\.(?:string|plurals|array)\.([A-Za-z0-9_]+)""")
    private val fromXml = Regex("""@(?:string|plurals|array)/([A-Za-z0-9_]+)""")

    // Relative to `:app`'s own directory, which is where a unit test runs.
    private fun sources(module: String): List<File> =
        File("../$module").walkTopDown()
            .filter { it.isFile && it.path.contains("/src/") }
            .filter { it.extension in setOf("kt", "xml") }
            .filter { "/res/values" !in it.path.replace(File.separatorChar, '/') }
            .toList()

    private fun declared(module: String): Map<String, String> {
        val folder = File("../$module/src/main/res/values")
        if (!folder.isDirectory) return emptyMap()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return buildMap {
            for (file in folder.listFiles().orEmpty().filter { it.name.endsWith(".xml") }) {
                val document = builder.parse(file)
                for (tag in listOf("string", "plurals", "string-array")) {
                    val nodes = document.getElementsByTagName(tag)
                    for (index in 0 until nodes.length) {
                        val element = nodes.item(index) as Element
                        // An item inside a <plurals> carries no name of its own.
                        val name = element.getAttribute("name")
                        if (name.isNotEmpty()) put(name, "$module/${file.name}")
                    }
                }
            }
        }
    }

    @Test
    fun `every shipped string is drawn by something`() {
        val used = buildSet {
            for (module in modules) {
                for (file in sources(module)) {
                    val text = file.readText()
                    fromKotlin.findAll(text).forEach { add(it.groupValues[1]) }
                    fromXml.findAll(text).forEach { add(it.groupValues[1]) }
                }
            }
        }

        val dead = buildMap {
            for (module in modules) {
                for ((name, where) in declared(module)) {
                    if (name !in used) put(name, where)
                }
            }
        }

        assertEquals(
            "These strings are declared and translated but nothing draws them. " +
                "Delete them, or draw them — a string kept «for later» is one a " +
                "proofreader will be asked to translate for a screen that does not exist.",
            emptyMap<String, String>(),
            dead,
        )
    }
}

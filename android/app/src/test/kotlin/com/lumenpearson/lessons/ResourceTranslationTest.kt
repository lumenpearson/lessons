package com.lumenpearson.lessons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The guard on the English translation.
 *
 * A missing translation is not a missing screen: Android resolves every name on
 * its own, so a string absent from `values-en/` quietly comes back from
 * `values/` — in Russian — in the middle of an otherwise English page. Nothing
 * fails, nothing warns, and the only way to find it is to read every screen in
 * both languages. This test reads the two folders instead.
 *
 * It parses the XML out of the source tree rather than going through `R`,
 * because `R` cannot answer the question: by the time resources are compiled,
 * "this name is missing from English" has already been turned into "this name
 * falls back", which is the behaviour under test.
 *
 * It reads **every** module that ships strings, not only `:app`. It used to
 * read one, which is how three of the four went unguarded: `:core:designsystem`
 * draws the hero card, `:widget` draws the home screen and `:core:data` words
 * its own failures, and a name missing from any of their `values-en/` falls
 * back exactly the same way. Reading the source tree is what makes that
 * possible from a test that lives in `:app`.
 */
class ResourceTranslationTest {

    /**
     * One translatable resource, by name, with the format arguments it uses.
     *
     * Plurals collapse into one entry: their items differ only in quantity, and
     * a plural whose forms disagree about their placeholders is a bug this
     * catches as a mismatch against the Russian rather than one worth a
     * category of its own.
     */
    private data class Resource(val name: String, val arguments: Set<String>)


    @Test
    fun `every translatable string has an English counterpart`() {
        val missing = modules.flatMap { module ->
            (module.default.keys - module.english.keys).sorted().map { "${module.module}/$it" }
        }
        assertTrue(
            "Missing from values-en/: $missing. Android would fall back to the " +
                "Russian for each of these, mid-screen.",
            missing.isEmpty(),
        )
    }

    /**
     * The other direction. A name only English has is either a typo or a
     * leftover, and both mean a row somewhere is reading a string the default
     * locale does not have.
     */
    @Test
    fun `values-en carries nothing the default locale does not`() {
        val extra = modules.flatMap { module ->
            (module.english.keys - module.default.keys).sorted().map { "${module.module}/$it" }
        }
        assertTrue("Only in values-en/: $extra", extra.isEmpty())
    }

    /**
     * Same arguments, whatever their order in the sentence.
     *
     * Compared as a set of "position + conversion" so that a translation is free
     * to reorder them — as long as it says so with explicit positional
     * arguments, which is the only way `%2$s` can come first without taking the
     * wrong value with it.
     */
    @Test
    fun `placeholders match between Russian and English`() {
        val mismatched = modules.flatMap { module ->
            module.default.values.mapNotNull { russian ->
                val english = module.english[russian.name] ?: return@mapNotNull null
                if (russian.arguments == english.arguments) {
                    null
                } else {
                    "${module.module}/${russian.name}: " +
                        "ru=${russian.arguments.sorted()} en=${english.arguments.sorted()}"
                }
            }
        }.sorted()
        assertTrue(
            "Format arguments differ between the two locales: $mismatched",
            mismatched.isEmpty(),
        )
    }

    /**
     * English has two plural forms and Russian has four.
     *
     * `getQuantityString` picks by the rules of the locale the string is read
     * in, so a `few` or a `many` in an English plural is never selected and a
     * missing `other` is a crash waiting for the right number.
     */
    @Test
    fun `English plurals carry the English quantities`() {
        val wrong = modules.flatMap { module ->
            module.englishPlurals
                .filterValues { it != setOf("one", "other") }
                .map { (name, quantities) -> "${module.module}/$name: ${quantities.sorted()}" }
        }.sorted()
        assertTrue(
            "English plurals must have exactly one/other: $wrong",
            wrong.isEmpty(),
        )
    }

    /** The mirror is per file, not just per name; a stray file would be invisible above. */
    @Test
    fun `every Russian strings file has an English file beside it`() {
        for (module in modules) {
            assertEquals(
                module.module,
                module.defaultFiles.map { it.name }.sorted(),
                module.englishFiles.map { it.name }.sorted(),
            )
        }
    }

    /** The guard is only as wide as the list it walks, so the list is asserted. */
    @Test
    fun `every module that ships strings is covered`() {
        assertEquals(
            listOf("app", "core/data", "core/designsystem", "widget"),
            modules.map { it.module },
        )
    }

    /** One module's two folders, already parsed. */
    private data class Translations(
        val module: String,
        val defaultFiles: List<File>,
        val englishFiles: List<File>,
        val default: Map<String, Resource>,
        val english: Map<String, Resource>,
        val englishPlurals: Map<String, Set<String>>,
    )

    private val modules: List<Translations> by lazy {
        resDirectories.map { (module, res) ->
            val defaultFiles = stringFiles(res, "values")
            val englishFiles = stringFiles(res, "values-en")
            Translations(
                module = module,
                defaultFiles = defaultFiles,
                englishFiles = englishFiles,
                default = parse(defaultFiles),
                english = parse(englishFiles),
                englishPlurals = pluralQuantities(englishFiles),
            )
        }
    }

    private companion object {

        /**
         * `strings.xml` and every `strings_*.xml` beside it, so a new file of
         * copy is covered the day it is added rather than the day somebody
         * remembers to list it here.
         */
        fun stringFiles(res: File, folder: String): List<File> {
            val directory = File(res, folder)
            assertTrue("No $folder/ in ${res.absolutePath}", directory.isDirectory)
            return directory.listFiles()
                .orEmpty()
                .filter { it.name.startsWith("strings") && it.name.endsWith(".xml") }
                .sortedBy { it.name }
                .also { assertTrue("No strings files in $folder/", it.isNotEmpty()) }
        }

        /**
         * Every module's `src/main/res` that has a `values/strings.xml`, paired
         * with the module path, found from wherever the test runner happens to
         * have started. Gradle runs unit tests with the module directory as the
         * working directory; running them from the repository root, as an IDE
         * sometimes does, has to work too — so the walk is upwards, for the
         * directory holding `settings.gradle.kts`.
         *
         * Discovered rather than listed, so a module that starts shipping
         * strings is guarded the day it does, not the day somebody remembers.
         */
        val resDirectories: List<Pair<String, File>> by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                if (File(directory, "settings.gradle.kts").isFile) {
                    val root = directory
                    val candidates = root.listFiles().orEmpty().flatMap { child ->
                        listOf(child) + child.listFiles().orEmpty().toList()
                    }
                    return@lazy candidates
                        .filter { File(it, "src/main/res/values/strings.xml").isFile }
                        .map { it.relativeTo(root).invariantSeparatorsPath to File(it, "src/main/res") }
                        .sortedBy { it.first }
                        .also { assertTrue("No module ships strings under $root", it.isNotEmpty()) }
                }
                directory = directory.parentFile
            }
            error("Could not find the Gradle root from ${File("").absolutePath}")
        }

        /** Named resources, skipping anything the Russian marks as untranslatable. */
        fun parse(files: List<File>): Map<String, Resource> = buildMap {
            for (element in files.flatMap(::elementsOf)) {
                if (element.tagName != "string" && element.tagName != "plurals") continue
                if (element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                check(!containsKey(name)) { "Duplicate resource name: $name" }
                val arguments = if (element.tagName == "plurals") {
                    pluralArguments(element)
                } else {
                    formatArguments(element.textContent)
                }
                put(name, Resource(name, arguments))
            }
        }

        fun pluralQuantities(files: List<File>): Map<String, Set<String>> = buildMap {
            for (element in files.flatMap(::elementsOf)) {
                if (element.tagName != "plurals") continue
                val quantities = element.getElementsByTagName("item")
                put(
                    element.getAttribute("name"),
                    (0 until quantities.length)
                        .map { (quantities.item(it) as Element).getAttribute("quantity") }
                        .toSet(),
                )
            }
        }

        fun elementsOf(file: File): List<Element> {
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file)
            val children = document.documentElement.childNodes
            return (0 until children.length)
                .map(children::item)
                .filterIsInstance<Element>()
                .filter { it.nodeType == Node.ELEMENT_NODE }
        }

        /**
         * The format arguments of a string, as "index:conversion" pairs.
         *
         * An argument without an explicit index takes the next position, which
         * is what `String.format` does with it, so `%s %s` and `%1$s %2$s`
         * compare equal — and a translation that reorders them has to spell the
         * indices out, which is exactly the rule this is here to enforce.
         */
        fun formatArguments(text: String): Set<String> {
            var next = 1
            return FormatArgument.findAll(text).mapNotNull { match ->
                val conversion = match.groupValues[2]
                if (conversion == "%") return@mapNotNull null
                val index = match.groupValues[1].removeSuffix("$").toIntOrNull() ?: next++
                "$index:$conversion"
            }.toSet()
        }

        /**
         * A `<plurals>`, measured per form rather than over the whole element.
         *
         * Reading `textContent` off the element concatenates every form, and an
         * argument with no explicit index takes the next position — so four
         * Russian forms of «%d урока» came out as `1:d 2:d 3:d 4:d` against
         * English's `1:d 2:d`, and the two locales could not agree by
         * construction. `:app` never showed it because every plural there
         * spells `%1$d`; the widget's spells `%d`, which is equally valid and
         * is what this measured wrong.
         */
        fun pluralArguments(element: Element): Set<String> {
            val items = element.getElementsByTagName("item")
            return (0 until items.length)
                .flatMap { formatArguments(items.item(it).textContent) }
                .toSet()
        }

        /** `%1$s`, `%d`, `%.2f`, `%%` — enough of the grammar to tell them apart. */
        val FormatArgument = Regex("""%(\d+\$)?[-#+0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
    }
}

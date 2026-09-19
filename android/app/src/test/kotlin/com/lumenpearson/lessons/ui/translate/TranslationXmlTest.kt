package com.lumenpearson.lessons.ui.translate

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export, tested the only way it is worth testing: by reading it back.
 *
 * Asserting that an apostrophe comes out as `\'` proves that the escaper does
 * what the escaper was written to do, which is not the question. The question
 * is whether a correction typed on a phone survives being written into a file
 * and compiled back into a string, and that is two decoders away — the XML
 * parser first, then the resource compiler's own backslash and quoting rules.
 * [decodeAsResourceCompiler] is a small model of the second one, and every case
 * below goes through both.
 */
class TranslationXmlTest {

    @Test
    fun `plain text survives untouched`() {
        assertRoundTrip("Расписание на неделю")
        assertEquals("Расписание", TranslationXml.escape("Расписание"))
    }

    /** The escape that `aapt` demands and XML does not care about at all. */
    @Test
    fun `an apostrophe survives`() {
        assertEquals("Don\\'t", TranslationXml.escape("Don't"))
        assertRoundTrip("Don't")
        assertRoundTrip("Сегодня уроков нет — это 'окно'")
    }

    /** The escape XML demands and `aapt` does not. */
    @Test
    fun `an ampersand and angle brackets survive`() {
        assertEquals("&amp;", TranslationXml.escape("&"))
        assertEquals("&lt;b&gt;", TranslationXml.escape("<b>"))
        assertRoundTrip("Retrofit & OkHttp")
        assertRoundTrip("1 < 2 > 0")
        // The ampersand of an entity must not be escaped a second time.
        assertEquals("&lt;a &amp;&gt;", TranslationXml.escape("<a &>"))
    }

    /**
     * The one thing that must *not* be escaped.
     *
     * A format specifier reaches the exporter as it sits in the file, because
     * the string was read as a template and never formatted. Turning `%` into
     * `%%` here would hand back a fragment whose arguments have become literal
     * percent signs.
     */
    @Test
    fun `format specifiers are left alone`() {
        assertEquals("%1\$s из %2\$d", TranslationXml.escape("%1\$s из %2\$d"))
        assertRoundTrip("%1\$s из %2\$d")
        assertRoundTrip("Заряд 100%")
        assertRoundTrip("%s")
    }

    @Test
    fun `quotes and backslashes survive`() {
        assertEquals("\\\"5\\\"", TranslationXml.escape("\"5\""))
        assertRoundTrip("Оценка \"5\"")
        assertRoundTrip("C:\\Users")
        assertRoundTrip("\\")
    }

    @Test
    fun `newlines and tabs survive`() {
        assertEquals("Первая\\nВторая", TranslationXml.escape("Первая\nВторая"))
        assertRoundTrip("Первая\nВторая")
        assertRoundTrip("Колонка\tКолонка")
        // Windows line endings arrive from a paste and are not a third case.
        assertEquals("a\\nb", TranslationXml.escape("a\r\nb"))
    }

    /**
     * Whitespace the resource compiler would otherwise eat: the edges, which it
     * trims, and any run in the middle, which it collapses.
     */
    @Test
    fun `spaces at the edges and in runs survive`() {
        assertEquals("\" до \"", TranslationXml.escape(" до "))
        assertRoundTrip(" до ")
        assertRoundTrip("два  пробела")
        assertRoundTrip("  ")
    }

    /** A value opening with @ or ? would otherwise be read as a reference. */
    @Test
    fun `a leading reference marker survives`() {
        assertEquals("\\@здесь", TranslationXml.escape("@здесь"))
        assertEquals("\\?как", TranslationXml.escape("?как"))
        assertRoundTrip("@здесь")
        assertRoundTrip("?как")
        // Only the first character is special; a marker inside is ordinary.
        assertEquals("почта@пример", TranslationXml.escape("почта@пример"))
        assertRoundTrip("почта@пример")
    }

    @Test
    fun `an empty correction produces an empty element body`() {
        assertEquals("", TranslationXml.escape(""))
    }

    /** Russian is the default locale, so its folder carries no qualifier. */
    @Test
    fun `the values folder follows the locale`() {
        assertEquals("values", TranslationXml.valuesDirectory("ru"))
        assertEquals("values-en", TranslationXml.valuesDirectory("en"))
        assertEquals("values", TranslationXml.valuesDirectory(""))
    }

    /**
     * And the module follows the key.
     *
     * Correction mode reaches the design system's own rows and cards, so a
     * session can hold a `ds_` string. Sent to `:app`, it would not fix the
     * library string — it would declare a second one that shadows it.
     */
    @Test
    fun `the values folder follows the module the string lives in`() {
        assertEquals(
            "android/app/src/main/res/values",
            TranslationXml.valuesFolder("settings_title", "ru"),
        )
        assertEquals(
            "android/core/designsystem/src/main/res/values-en",
            TranslationXml.valuesFolder("ds_state_break", "en"),
        )
        assertEquals(
            "android/core/data/src/main/res/values",
            TranslationXml.valuesFolder("alert_morning_title", "ru"),
        )
        assertEquals(
            "android/widget/src/main/res/values",
            TranslationXml.valuesFolder("widget_loading", "ru"),
        )
    }

    /**
     * The prefix rule, read off the tree rather than believed.
     *
     * [TranslationXml.moduleOf] routes a correction by the prefix of its key,
     * because by the time an id exists every module's resources have been
     * merged under one package and nothing can tell them apart any more. That
     * works only for as long as each module really does keep to its prefix —
     * and nothing but this said so.
     */
    @Test
    fun `every module's strings carry the prefix the export routes them by`() {
        val strays = modules.flatMap { (module, names) ->
            names.filterNot { TranslationXml.moduleOf(it) == module }
                .map { "$it (in $module, routed to ${TranslationXml.moduleOf(it)})" }
        }
        assertTrue(
            "These strings would be exported into the wrong module's values/, where " +
                "they would shadow the real one instead of fixing it: $strays",
            strays.isEmpty(),
        )
    }

    /** Module directory to the names declared in its default `values/`. */
    private val modules: Map<String, List<String>> by lazy {
        listOf("app", "core/designsystem", "core/data", "widget").associateWith { module ->
            File(root, "android/$module/src/main/res/values").listFiles().orEmpty()
                .filter { it.extension == "xml" }
                .flatMap { file ->
                    Regex("<(?:string|plurals) name=\"([^\"]+)\"")
                        .findAll(file.readText())
                        .map { it.groupValues[1] }
                        .toList()
                }
        }
    }

    /**
     * The repository root, found from wherever the runner started — the same
     * walk `ResourceTranslationTest` makes, and for the same reason.
     */
    private val root: File by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "android/settings.gradle.kts").isFile) return@lazy directory
            directory = directory.parentFile
        }
        error("Could not find the repository root from ${File("").absolutePath}")
    }

    @Test
    fun `an empty session exports nothing`() {
        assertEquals("", TranslationXml.fragment(emptyList()))
    }

    /**
     * Grouped by folder, sorted inside it, and indented as the files are — the
     * same session has to produce the same text twice, or two exports of one
     * session read as a diff of the order things were tapped in.
     */
    @Test
    fun `the fragment is grouped by folder and ordered`() {
        val fragment = TranslationXml.fragment(
            listOf(
                TranslationEdit("week_title", "ru", "Неделя", "Неделя целиком"),
                TranslationEdit("today_title", "en", "Today", "Today's lessons"),
                TranslationEdit("homework_title", "ru", "Домашка", "Домашнее задание"),
                TranslationEdit("ds_state_break", "ru", "Перемена", "Отдых"),
            ),
        )
        assertEquals(
            """
            |    <!-- android/app/src/main/res/values/ -->
            |    <string name="homework_title">Домашнее задание</string>
            |    <string name="week_title">Неделя целиком</string>
            |
            |    <!-- android/app/src/main/res/values-en/ -->
            |    <string name="today_title">Today\'s lessons</string>
            |
            |    <!-- android/core/designsystem/src/main/res/values/ -->
            |    <string name="ds_state_break">Отдых</string>
            |
            """.trimMargin(),
            fragment,
        )
    }

    /** Everything in one fragment still reads back one value at a time. */
    @Test
    fun `a whole fragment parses and decodes`() {
        val edits = listOf(
            TranslationEdit("a", "ru", "", "Том & Джерри"),
            TranslationEdit("b", "ru", "", "Не 'то' <b>"),
            TranslationEdit("c", "ru", "", " %1\$s из %2\$d "),
        )
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse("<resources>\n${TranslationXml.fragment(edits)}</resources>".byteInputStream())
        val strings = document.getElementsByTagName("string")
        val decoded = (0 until strings.length).associate { index ->
            val element = strings.item(index)
            element.attributes.getNamedItem("name").nodeValue to
                decodeAsResourceCompiler(element.textContent)
        }
        assertEquals(
            mapOf(
                "a" to "Том & Джерри",
                "b" to "Не 'то' <b>",
                "c" to " %1\$s из %2\$d ",
            ),
            decoded,
        )
    }

    private fun assertRoundTrip(value: String) {
        assertEquals(value, roundTrip(value))
    }

    /** [value] escaped, written into an element, parsed, and decoded back. */
    private fun roundTrip(value: String): String {
        val xml = "<resources><string name=\"k\">${TranslationXml.escape(value)}</string></resources>"
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        return decodeAsResourceCompiler(document.getElementsByTagName("string").item(0).textContent)
    }

    private companion object {

        /**
         * What `aapt` does to an element's text once the XML parser has already
         * turned the entities back into characters.
         *
         * A backslash escapes the character after it, an unescaped double quote
         * toggles a region where whitespace is kept verbatim, and outside such a
         * region every run of spaces collapses to one and the ends are trimmed.
         * That last rule is the one nobody expects and the reason the escaper
         * quotes values whose spaces matter.
         */
        fun decodeAsResourceCompiler(raw: String): String {
            val decoded = StringBuilder()
            var quoting = false
            var pendingSpace = false
            var index = 0

            fun appendLiteral(character: Char) {
                if (pendingSpace && decoded.isNotEmpty()) decoded.append(' ')
                pendingSpace = false
                decoded.append(character)
            }

            while (index < raw.length) {
                val character = raw[index]
                when {
                    character == '\\' && index + 1 < raw.length -> {
                        when (val escaped = raw[index + 1]) {
                            'n' -> appendLiteral('\n')
                            't' -> appendLiteral('\t')
                            else -> appendLiteral(escaped)
                        }
                        index += 2
                    }
                    character == '"' -> {
                        quoting = !quoting
                        index++
                    }
                    quoting -> {
                        decoded.append(character)
                        index++
                    }
                    character == ' ' -> {
                        pendingSpace = true
                        index++
                    }
                    else -> {
                        appendLiteral(character)
                        index++
                    }
                }
            }
            return decoded.toString()
        }
    }
}

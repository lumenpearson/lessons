package com.lumenpearson.lessons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * A row in a group is titled, not explained.
 *
 * A group row's title is one line, and a line that does not fit scrolls rather
 * than wraps — so a sentence put there is read a few words at a time, cut at
 * both ends. «Для разработчиков» explained who may open it that way, in both of
 * its states (#256): the one explanation on the page, sliding past. The
 * subtitle is where a sentence goes; it wraps.
 *
 * Asked of the source, as [MarqueeClockTest] asks: which string each row is
 * handed as its title, and whether the Russian of it — the source language —
 * ends a sentence. A title of the app's own words never does.
 */
class RowTitleSentenceTest {

    @Test
    fun `no group row is titled with a sentence`() {
        val sentences = rowTitles.filter { (_, name) -> russian[name]?.trimEnd()?.endsWith('.') == true }
            .map { (file, name) -> "$file: $name «${russian[name]}»" }
        assertTrue(
            "These rows take a sentence as their one-line title, where it scrolls instead of " +
                "wrapping. Give each a short title and put the sentence in the subtitle:\n" +
                sentences.joinToString("\n"),
            sentences.isEmpty(),
        )
    }

    /**
     * A pattern that stopped matching would find no rows, and the test above
     * would pass over nothing — so what it scans is pinned.
     */
    @Test
    fun `the scan finds the rows and their strings`() {
        assertTrue("only ${rowTitles.size} row titles found", rowTitles.size > 100)
        val unknown = rowTitles.map { it.second }.filterNot { it in russian }
        assertTrue("row titles naming no string in values/: $unknown", unknown.isEmpty())
    }

    /** (file, string name) for every group row titled from a string resource. */
    private val rowTitles: List<Pair<String, String>> by lazy {
        File(root, "app/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                RowTitle.findAll(file.readText()).map { file.name to it.groupValues[1] }
            }
            .toList()
    }

    /** The source language's strings, by name. */
    private val russian: Map<String, String> by lazy {
        File(root, "app/src/main/res/values").listFiles().orEmpty()
            .filter { it.name.startsWith("strings") && it.name.endsWith(".xml") }
            .flatMap { file ->
                val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    .documentElement.getElementsByTagName("string")
                (0 until strings.length).map { strings.item(it) as Element }
            }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private val root: File by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return@lazy directory
            directory = directory.parentFile
        }
        error("Could not find the Gradle root from ${File("").absolutePath}")
    }

    private companion object {

        /** `GroupItem(title = correctedString(R.string.x` and its siblings, across line breaks. */
        val RowTitle = Regex(
            """\bGroup(?:Switch|Link|Slider|Time)?Item\(\s*(?:title\s*=\s*)?""" +
                """corrected(?:String|Line)\(\s*R\.string\.(\w+)""",
        )
    }
}

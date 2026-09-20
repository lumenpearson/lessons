package com.lumenpearson.lessons.core.data.docs

import com.lumenpearson.lessons.core.model.DocsBlock
import com.lumenpearson.lessons.core.model.DocsGuide
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two guides, read out of the repository and held level with each other.
 *
 * This is `ResourceTranslationTest`'s job for the text that stopped being a
 * resource. The Russian file is the source and the English one is the
 * translation, exactly as `values/` and `values-en/` are, and the same failure
 * is possible: a page added to one and not the other is not a missing screen —
 * it is a toolbar with a different number of buttons depending on the language,
 * with nothing logged.
 *
 * It reads the files rather than the parsed output of a fixture, because the
 * files are what ships: the app fetches these bytes from the repository and the
 * build copies them into the APK. A test over a fixture would pass while the
 * shipped guide was broken.
 */
class DocsGuideParityTest {

    private val folder: File = repositoryRoot().resolve("docs/app")

    private fun read(language: String): DocsGuide {
        val file = folder.resolve("guide.$language.md")
        assertTrue("${file.path} is missing — the app ships it", file.isFile)
        return DocsMarkdown.parse(file.readText(), language)
    }

    private val russian: DocsGuide by lazy { read("ru") }
    private val english: DocsGuide by lazy { read("en") }

    @Test
    fun `both guides parse into pages`() {
        // A silent pass here would make every other test in this file vacuous:
        // two empty guides agree about everything.
        assertTrue("the Russian guide has no pages", russian.pages.size >= 5)
        assertEquals(
            "the two guides describe a different number of pages",
            russian.pages.size,
            english.pages.size,
        )
    }

    @Test
    fun `the pages are the same, in the same order`() {
        assertEquals(
            "page ids are what the icons and the remembered position are keyed " +
                "by, so they are not translated",
            russian.pages.map { it.id },
            english.pages.map { it.id },
        )
    }

    @Test
    fun `every page says something in both languages`() {
        val empty = (russian.pages + english.pages).filter {
            it.title.isBlank() || it.label.isBlank() || it.blocks.isEmpty()
        }
        assertTrue(
            "a page with no title, no toolbar label or no content: $empty",
            empty.isEmpty(),
        )
    }

    @Test
    fun `the two languages are built from the same blocks`() {
        // Not the same words — the same shapes. A paragraph that became a list
        // in translation, or four points that became three, is a page that
        // answers a different question depending on the language.
        russian.pages.zip(english.pages).forEach { (ru, en) ->
            assertEquals(
                "page «${ru.id}» is built differently in the two languages",
                ru.blocks.map(::shapeOf),
                en.blocks.map(::shapeOf),
            )
        }
    }

    @Test
    fun `no page smuggles markdown the parser does not understand`() {
        // What is not understood is drawn as the characters it is made of, so
        // the failure is visible rather than dangerous — but it is still a line
        // of asterisks on somebody's screen. The marks this checks for are the
        // ones a writer reaches for out of habit.
        val unsupported = listOf("![", "<table", "|---", "\t")
        val offenders = (russian.pages + english.pages).flatMap { page ->
            page.blocks.flatMap { textOf(it) }
                .filter { line -> unsupported.any(line::contains) }
                .map { "${page.id}: $it" }
        }
        assertTrue(
            "the parser understands paragraphs, lists, steps, notes, bold, " +
                "code and links, and nothing else:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the manifest names a file for each language and an app version`() {
        val manifest = folder.resolve("manifest.json")
        assertTrue("${manifest.path} is missing", manifest.isFile)
        val text = manifest.readText()
        listOf("\"version\"", "\"updated\"", "\"appVersion\"", "guide.ru.md", "guide.en.md")
            .forEach { field ->
                assertTrue("the manifest says nothing about $field", text.contains(field))
            }
    }

    private fun shapeOf(block: DocsBlock): String = when (block) {
        is DocsBlock.Paragraph -> "paragraph"
        is DocsBlock.Points -> "points(${block.items.size})"
        is DocsBlock.Note -> "note"
        is DocsBlock.Step -> "step(${block.number})"
    }

    private fun textOf(block: DocsBlock): List<String> = when (block) {
        is DocsBlock.Paragraph -> listOf(block.spans.joinToString("") { it.text })
        is DocsBlock.Note -> listOf(block.spans.joinToString("") { it.text })
        is DocsBlock.Points -> block.items.map { line -> line.spans.joinToString("") { it.text } }
        is DocsBlock.Step -> listOf(
            block.title.joinToString("") { it.text },
            block.text.joinToString("") { it.text },
        )
    }

    /**
     * The repository root, found by climbing rather than by counting `..`.
     *
     * A unit test runs with the module's own directory as its working
     * directory, and the guide is two levels above the Gradle root. Counting
     * the steps would break the moment a module moved; `settings.gradle.kts` is
     * the Gradle root, and the repository is its parent.
     */
    private fun repositoryRoot(): File {
        val start = File(".").absoluteFile
        val gradleRoot: File? = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        val root: File? = gradleRoot?.parentFile
        assertTrue("no settings.gradle.kts above ${start.path}", root != null)
        return checkNotNull(root)
    }
}

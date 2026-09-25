package com.lumenpearson.lessons.core.data.legal

import com.lumenpearson.lessons.core.data.docs.DocsMarkdown
import com.lumenpearson.lessons.core.model.DocsBlock
import com.lumenpearson.lessons.core.model.DocsGuide
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terms of use and the privacy policy, read out of the repository.
 *
 * `DocsGuideParityTest`'s job for the second pair of texts the app ships: the
 * files in `docs/legal/` are the bytes the APK carries and the pages the
 * published link opens, so a fixture would pass while the shipped text was
 * broken. What is at stake is worse than a guide page in one language only —
 * the line on the first screen tells a reader they accept these two texts, and
 * a section that exists in Russian and not in English is a policy that says
 * different things depending on the phone's language.
 */
class LegalDocumentsTest {

    private val folder: File = repositoryRoot().resolve("docs/legal")

    private fun read(document: LegalDocument, language: String): DocsGuide {
        val file = folder.resolve("${document.slug}.$language.md")
        assertTrue("${file.path} is missing — the app ships it", file.isFile)
        return DocsMarkdown.parse(file.readText(), language)
    }

    private val all: List<Pair<LegalDocument, Pair<DocsGuide, DocsGuide>>> by lazy {
        LegalDocument.entries.map { it to (read(it, "ru") to read(it, "en")) }
    }

    @Test
    fun `every document exists in both languages and parses into sections`() {
        // A silent pass here would make every test below vacuous: two empty
        // documents agree about everything.
        all.forEach { (document, pair) ->
            val (ru, en) = pair
            assertTrue("${document.slug}.ru.md has too few sections", ru.pages.size >= 5)
            assertTrue("${document.slug}.ru.md has no title", ru.title.isNotBlank())
            assertTrue("${document.slug}.en.md has no title", en.title.isNotBlank())
        }
    }

    @Test
    fun `the sections are the same, in the same order`() {
        all.forEach { (document, pair) ->
            val (ru, en) = pair
            assertEquals(
                "${document.slug}: section ids are not translated, and a section " +
                    "one language has and the other lacks is a different policy",
                ru.pages.map { it.id },
                en.pages.map { it.id },
            )
        }
    }

    @Test
    fun `every section id is written in the file rather than guessed from its heading`() {
        // A section with no metadata comment directly under its heading gets
        // the heading upper-cased as its id, which differs between the
        // languages. Read from the raw lines, because the parsed id cannot tell
        // a written «AVAILABILITY» from one guessed from «Availability».
        LegalDocument.entries.forEach { document ->
            listOf("ru", "en").forEach { lang ->
                val lines = folder.resolve("${document.slug}.$lang.md").readLines()
                lines.forEachIndexed { index, line ->
                    if (!line.startsWith("## ")) return@forEachIndexed
                    val next = lines.getOrNull(index + 1).orEmpty().trim()
                    assertTrue(
                        "${document.slug}.$lang.md: «$line» is not followed by `<!-- id: …; label: … -->`",
                        next.startsWith("<!-- id:") && next.endsWith("-->"),
                    )
                }
            }
        }
    }

    @Test
    fun `every section says something in both languages`() {
        val empty = all.flatMap { (document, pair) ->
            (pair.first.pages + pair.second.pages)
                .filter { it.title.isBlank() || it.blocks.isEmpty() }
                .map { "${document.slug}: ${it.id}" }
        }
        assertTrue("sections with no heading or no text: $empty", empty.isEmpty())
    }

    @Test
    fun `the two languages are built from the same blocks`() {
        all.forEach { (document, pair) ->
            pair.first.pages.zip(pair.second.pages).forEach { (ru, en) ->
                assertEquals(
                    "${document.slug} «${ru.id}» is built differently in the two languages",
                    ru.blocks.map(::shapeOf),
                    en.blocks.map(::shapeOf),
                )
            }
        }
    }

    @Test
    fun `no document smuggles markdown the parser does not understand`() {
        // The same marks DocsGuideParityTest refuses for the guide, for the same
        // reason: what is not understood is drawn as its characters — a table
        // of the data the server keeps would arrive as a wall of pipes.
        val unsupported = listOf("![", "<table", "|---", "\t")
        val offenders = lines().filter { (_, line) -> unsupported.any(line::contains) }
        assertTrue(
            "the parser understands paragraphs, lists, steps, notes, bold, code and " +
                "links, and nothing else:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every link is absolute https`() {
        // A relative link works on GitHub and is dead in the copy the APK
        // carries, which is the copy an offline reader is shown.
        val links = all.flatMap { (document, pair) ->
            (pair.first.pages + pair.second.pages).flatMap { page ->
                page.blocks.flatMap(::spansOf).mapNotNull { it.link }.map { document.slug to it }
            }
        }
        assertTrue("the documents link nothing, so this test proves nothing", links.isNotEmpty())
        val bad = links.filterNot { (_, link) -> link.startsWith("https://") }
        assertTrue("links a reader cannot follow from the app: $bad", bad.isEmpty())
    }

    @Test
    fun `no template marker is left`() {
        // A fork fills these in; a marker left behind is drawn on screen,
        // inside the text a reader is told they accept.
        val markers = listOf("{{", "TODO", "⟨", "[заполн", "{OPERATOR}", "{CONTACT}")
        val raw = LegalDocument.entries.flatMap { document ->
            listOf("ru", "en").map { lang ->
                val name = "${document.slug}.$lang.md"
                name to folder.resolve(name).readText()
            }
        }
        val left = raw.flatMap { (name, text) ->
            // Comments are for whoever opens the file and are not drawn; the
            // FORK notes in them may name the fields a fork fills.
            val drawn = text.replace(Regex("(?s)<!--.*?-->"), "")
            markers.filter(drawn::contains).map { "$name: $it" }
        }
        assertTrue("template markers in the shipped text: $left", left.isEmpty())
    }

    @Test
    fun `the manifest names every file and an edition and an effective date`() {
        val file = folder.resolve(MANIFEST)
        assertTrue("${file.path} is missing", file.isFile)
        val manifest = checkNotNull(parseLegalManifest(file.readText())) { "legal.json does not parse" }
        val edition = manifest.toEdition()
        assertTrue("the manifest names no usable edition and date: $manifest", edition != null)
        LegalDocument.entries.forEach { document ->
            listOf("ru", "en").forEach { lang ->
                val name = manifest.files[document.slug]?.get(lang)
                assertEquals(
                    "the manifest must name ${document.slug}.$lang.md — the build's " +
                        "address is built from that convention, so a different name " +
                        "would publish one file and bundle another",
                    "${document.slug}.$lang.md",
                    name,
                )
            }
        }
    }

    @Test
    fun `the reader opens every bundled document the way the app does`() {
        LegalDocument.entries.forEach { document ->
            listOf("ru-RU", "en", "uk").forEach { language ->
                val text = readLegal(
                    open = { name -> folder.resolve(name).takeIf { it.isFile }?.readText() },
                    document = document,
                    language = language,
                )
                assertTrue("${document.slug} in $language did not open", text != null)
                assertTrue("${document.slug} in $language has no edition", text?.edition != null)
            }
        }
    }

    /**
     * Every folder mounted at the root of the APK's assets, with no name in
     * two of them.
     *
     * `docs/app/` (the guide, from `:core:data`), `docs/legal/` (from `:app`)
     * and the region catalog all land in one flat folder, and `DocsStore` opens
     * `manifest.json` there. Whichever of two same-named files the merger kept,
     * one reader would be handed the other's file — which is why this folder's
     * manifest is `legal.json`.
     */
    @Test
    fun `no file in docs legal has the name of one in another asset folder`() {
        val root = repositoryRoot()
        val folders = listOf("docs/app", "docs/legal", "server/app/catalog/data")
            .map(root::resolve)
            .filter(File::isDirectory)
        assertTrue("docs/app and docs/legal must both exist", folders.size >= 2)
        val owners = mutableMapOf<String, String>()
        val clashes = mutableListOf<String>()
        folders.forEach { dir ->
            dir.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                val previous = owners.put(file.name, dir.path)
                if (previous != null) clashes += "${file.name}: $previous and ${dir.path}"
            }
        }
        assertTrue("two asset folders ship a file of one name: $clashes", clashes.isEmpty())
    }

    private fun lines(): List<Pair<String, String>> = all.flatMap { (document, pair) ->
        (pair.first.pages + pair.second.pages).flatMap { page ->
            page.blocks.flatMap(::textOf).map { "${document.slug}/${page.id}" to it }
        }
    }

    private fun shapeOf(block: DocsBlock): String = when (block) {
        is DocsBlock.Paragraph -> "paragraph"
        is DocsBlock.Points -> "points(${block.items.size})"
        is DocsBlock.Note -> "note"
        is DocsBlock.Step -> "step(${block.number})"
    }

    private fun spansOf(block: DocsBlock) = when (block) {
        is DocsBlock.Paragraph -> block.spans
        is DocsBlock.Note -> block.spans
        is DocsBlock.Points -> block.items.flatMap { it.spans }
        is DocsBlock.Step -> block.title + block.text
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

    /** The repository root: the parent of the Gradle root, found by climbing. */
    private fun repositoryRoot(): File {
        val start = File(".").absoluteFile
        val gradleRoot: File? = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        return checkNotNull(gradleRoot?.parentFile) { "no settings.gradle.kts above ${start.path}" }
    }
}

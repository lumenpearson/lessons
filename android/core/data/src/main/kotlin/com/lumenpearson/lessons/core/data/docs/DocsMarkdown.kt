package com.lumenpearson.lessons.core.data.docs

import com.lumenpearson.lessons.core.model.DocsBlock
import com.lumenpearson.lessons.core.model.DocsGuide
import com.lumenpearson.lessons.core.model.DocsGuidePage
import com.lumenpearson.lessons.core.model.DocsLine
import com.lumenpearson.lessons.core.model.DocsSpan

/**
 * The Markdown the guide is written in, turned into the blocks the app draws.
 *
 * **A deliberately small subset, and the smallness is the point.** A general
 * Markdown library would bring tables, images, raw HTML and reference links —
 * every one of which the renderer would have to answer for, and none of which
 * the guide uses. What is understood here is exactly what `docs/app/guide.*.md`
 * contains:
 *
 *  * `#` — the document's own title, once, at the top;
 *  * `##` — a page, whose id, toolbar label and one-line summary are on the
 *    HTML comment directly under it;
 *  * a blank-line-separated run of text — a paragraph;
 *  * `-` or `*` at the start of consecutive lines — a list of peers;
 *  * `1.` with a bold lead — a numbered step, where the bold is its title;
 *  * `>` — the aside a skimmer must not skim past.
 *
 * Inside a line it understands `**bold**`, `` `code` `` and `[text](address)`,
 * and nothing else. Anything it does not recognise stays as the characters it
 * is made of, which is the only safe answer: a guide that silently dropped a
 * line it could not parse would be missing a sentence nobody would think to
 * look for.
 *
 * It never throws. A file that is empty, truncated mid-page or not Markdown at
 * all parses to a guide with no pages, and the caller decides what to do about
 * that — which for the repository is "keep what was already stored". Parsing is
 * the one step between the network and the screen, and a crash there would take
 * the whole documentation away over one bad byte.
 */
internal object DocsMarkdown {

    /** `<!-- id: START; label: Старт; summary: … -->`, on one line. */
    private val METADATA = Regex("""<!--(.*?)-->""")

    /** `1.`, `2.` … at the start of a line, with the number kept. */
    private val ORDERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")

    /** `**Title** — text`, with an em dash, an en dash or a hyphen between. */
    private val STEP_TITLE = Regex("""^\*\*(.+?)\*\*\s*[—–-]\s*(.*)$""")

    /** A bold run, a code span or a link — whichever comes first. */
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")

    fun parse(markdown: String, language: String): DocsGuide {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val builder = GuideBuilder(language)
        lines.fold(false) { insideComment, raw ->
            builder.read(raw, insideComment)
        }
        return builder.build()
    }

    /**
     * The parse, as one object rather than a fold over a state class.
     *
     * A page is finished by the start of the next one and a block by a blank
     * line, so both are open while their lines arrive and there is no shape to
     * carry between calls that is not simply "what has been read so far".
     */
    private class GuideBuilder(private val language: String) {

        private val pages = mutableListOf<DocsGuidePage>()
        private val blocks = mutableListOf<DocsBlock>()
        private val pending = mutableListOf<String>()

        private var title: String = ""
        private var pageTitle: String? = null
        private var metadata: Map<String, String> = emptyMap()
        private var kind: Kind = Kind.NONE
        private var steps: Int = 0

        /** Reads one line; returns whether the next one is inside a comment. */
        fun read(raw: String, insideComment: Boolean): Boolean {
            val line = raw.trim()
            if (insideComment) return !line.endsWith("-->")
            if (line.startsWith("<!--")) {
                // The metadata comment is the one that is not skipped. Anything
                // else — the note at the top of the file, a translator's aside —
                // is for whoever opens the file, not for the screen.
                METADATA.find(line)?.let { match -> takeMetadata(match.groupValues[1]) }
                return !line.contains("-->")
            }
            when {
                line.isEmpty() -> flush()
                line.startsWith("## ") -> startPage(line.removePrefix("## ").trim())
                line.startsWith("# ") -> takeTitle(line.removePrefix("# ").trim())
                line.startsWith("> ") || line == ">" -> append(Kind.NOTE, line.removePrefix(">").trim())
                line.startsWith("- ") || line.startsWith("* ") -> append(Kind.POINTS, line.drop(2).trim())
                ORDERED.matches(line) -> appendStep(line)
                else -> append(Kind.PARAGRAPH, line)
            }
            return false
        }

        fun build(): DocsGuide {
            endPage()
            return DocsGuide(title = title, language = language, pages = pages.toList())
        }

        private fun takeTitle(value: String) {
            // Only the first `#` counts. A second one inside a page is a heading
            // the format has no room for, and overwriting the document's name
            // with it would put a section's name on the whole book.
            if (title.isEmpty() && pageTitle == null) title = value
        }

        private fun takeMetadata(body: String) {
            // Read before the page is started, because the comment sits under
            // the heading: `##` opens the page, this fills in what it is called
            // in the toolbar. A page whose metadata never arrives keeps the
            // fallbacks in `endPage`.
            val fields = body.split(';')
            metadata = fields.mapNotNull { field ->
                val at = field.indexOf(':')
                if (at <= 0) return@mapNotNull null
                field.take(at).trim().lowercase() to field.drop(at + 1).trim()
            }.toMap()
            // `summary` is the last field and may contain a `;` of its own, so
            // it is taken from the raw text rather than from the split above.
            val summaryAt = body.indexOf("summary:")
            if (summaryAt >= 0) {
                metadata = metadata + ("summary" to body.substring(summaryAt + "summary:".length).trim())
            }
        }

        private fun startPage(heading: String) {
            endPage()
            pageTitle = heading
            metadata = emptyMap()
            steps = 0
        }

        private fun endPage() {
            flush()
            val heading = pageTitle ?: return
            pages += DocsGuidePage(
                // A page with no id in the file still needs one that does not
                // change between languages, and there is nothing else to use.
                // It is why the parity test checks the ids rather than trusting
                // them: two translations of a heading are two different ids.
                id = metadata["id"]?.takeIf { it.isNotEmpty() } ?: heading.uppercase(),
                title = heading,
                label = metadata["label"]?.takeIf { it.isNotEmpty() } ?: heading,
                summary = metadata["summary"].orEmpty(),
                blocks = blocks.toList(),
            )
            blocks.clear()
            pageTitle = null
        }

        private fun append(next: Kind, text: String) {
            if (kind != next) flush()
            kind = next
            pending += text
        }

        private fun appendStep(line: String) {
            // Every step is its own block: unlike a paragraph, whose lines are
            // one sentence wrapped, two numbered lines are two stages.
            flush()
            val match = ORDERED.find(line) ?: return
            val number = match.groupValues[1].toIntOrNull() ?: (steps + 1)
            val body = match.groupValues[2].trim()
            val titled = STEP_TITLE.find(body)
            steps += 1
            blocks += DocsBlock.Step(
                number = number,
                title = spans(titled?.groupValues?.get(1) ?: ""),
                text = spans(titled?.groupValues?.get(2) ?: body),
            )
        }

        /** Closes whatever was open. Called on a blank line and on a heading. */
        private fun flush() {
            if (pending.isEmpty()) {
                kind = Kind.NONE
                return
            }
            when (kind) {
                // A paragraph or a note wrapped over several lines is one
                // sentence: the newlines are the file's, not the author's.
                Kind.PARAGRAPH -> blocks += DocsBlock.Paragraph(spans(pending.joinToString(" ")))
                Kind.NOTE -> blocks += DocsBlock.Note(spans(pending.joinToString(" ")))
                Kind.POINTS -> blocks += DocsBlock.Points(pending.map { DocsLine(spans(it)) })
                Kind.NONE -> Unit
            }
            pending.clear()
            kind = Kind.NONE
        }
    }

    private enum class Kind { NONE, PARAGRAPH, POINTS, NOTE }

    /**
     * One line of text, split at its marks.
     *
     * Marks do not nest: `**bold `code`**` is bold text containing backticks,
     * because supporting the nesting would mean a tree, and the guide has never
     * needed one. The runs between marks are kept exactly as they are, spaces
     * included, so that joining every span's text back together returns the
     * line without its punctuation — which is what `DocsMarkdownTest` checks.
     */
    internal fun spans(line: String): List<DocsSpan> {
        if (line.isEmpty()) return emptyList()
        val matches = INLINE.findAll(line).toList()
        if (matches.isEmpty()) return listOf(DocsSpan(line))
        val out = mutableListOf<DocsSpan>()
        var at = 0
        for (match in matches) {
            if (match.range.first > at) {
                out += DocsSpan(line.substring(at, match.range.first))
            }
            val groups = match.groupValues
            out += when {
                groups[1].isNotEmpty() -> DocsSpan(groups[1], bold = true)
                groups[2].isNotEmpty() -> DocsSpan(groups[2], code = true)
                else -> DocsSpan(groups[3], link = groups[4])
            }
            at = match.range.last + 1
        }
        if (at < line.length) out += DocsSpan(line.substring(at))
        return out.toList()
    }
}

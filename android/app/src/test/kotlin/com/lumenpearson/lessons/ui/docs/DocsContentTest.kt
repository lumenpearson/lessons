package com.lumenpearson.lessons.ui.docs

import com.lumenpearson.lessons.core.model.DocsBlock
import com.lumenpearson.lessons.core.model.DocsGuidePage
import com.lumenpearson.lessons.core.model.DocsLine
import com.lumenpearson.lessons.core.model.DocsSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the guide is laid out, once something else has decided what it says.
 *
 * The text itself is no longer here to check: it is markdown in `docs/app/`,
 * and `DocsGuideParityTest` in `:core:data` reads the shipped files and holds
 * the two languages level. What is left in this module is the arrangement — a
 * run of steps is one card, a shape is reused only by its own shape, the bar
 * knows which section is on screen — and none of it needs a device.
 */
class DocsContentTest {

    private fun text(value: String) = listOf(DocsSpan(value))

    private fun paragraph(value: String) = DocsBlock.Paragraph(text(value))

    private fun step(number: Int) = DocsBlock.Step(number, text("t$number"), text("b$number"))

    private fun page(id: String, vararg blocks: DocsBlock) = DocsGuidePage(
        id = id,
        title = "Title $id",
        label = id,
        summary = "Summary $id",
        blocks = blocks.toList(),
    )

    /** Consecutive steps become one run; everything else stands alone. */
    @Test
    fun `runs gather steps and leave prose alone`() {
        val blocks = listOf(
            paragraph("intro"),
            step(1),
            step(2),
            DocsBlock.Note(text("careful")),
            step(1),
        )

        assertEquals(
            listOf(
                listOf(blocks[0]),
                listOf(blocks[1], blocks[2]),
                listOf(blocks[3]),
                listOf(blocks[4]),
            ),
            docsRuns(blocks),
        )
    }

    @Test
    fun `runs keep every block exactly once`() {
        val blocks = listOf(
            paragraph("one"),
            step(1),
            step(2),
            DocsBlock.Points(listOf(DocsLine(text("a")), DocsLine(text("b")))),
            DocsBlock.Note(text("note")),
        )

        assertEquals(blocks, docsRuns(blocks).flatten())
    }

    @Test
    fun `a page with no blocks makes no runs`() {
        assertTrue(docsRuns(emptyList()).isEmpty())
    }

    /**
     * A shape is reused only by its own shape.
     *
     * The type exists to stop a paragraph being handed the slot a card of
     * points left behind. Two kinds sharing a string would do exactly that
     * while looking like an optimisation, and nothing on the screen would say
     * so — the list would simply throw the subtree away on every reuse, which
     * is the state this was meant to leave.
     */
    @Test
    fun `each kind of block draws under a content type of its own`() {
        val types = listOf(
            paragraph("p"),
            DocsBlock.Points(listOf(DocsLine(text("a")))),
            DocsBlock.Note(text("n")),
        ).map { docsContentType(listOf(it)) }

        assertEquals("Kinds sharing a content type: $types", types.size, types.toSet().size)
    }

    /**
     * A lone step and a stack of them are one shape, because they are one card.
     *
     * [docsRuns] gathers consecutive steps, so a page can hand the list either;
     * `DocsRun` draws both as the container, and a type that told them apart
     * would refuse a reuse that is actually correct.
     */
    @Test
    fun `a step is the same shape whether it stands alone or in a run`() {
        assertEquals(
            docsContentType(listOf(step(1))),
            docsContentType(listOf(step(1), step(2))),
        )
    }

    @Test
    fun `every run names one of the four shapes`() {
        val known = setOf("steps", "paragraph", "points", "note")
        val blocks = listOf(
            paragraph("p"),
            DocsBlock.Points(listOf(DocsLine(text("a")), DocsLine(text("b")))),
            DocsBlock.Note(text("n")),
            step(1),
            step(2),
        )

        docsRuns(blocks).forEach { run ->
            assertTrue("unknown content type ${docsContentType(run)}", docsContentType(run) in known)
        }
    }

    /** The selected item of the toolbar is an index into the list it was handed. */
    @Test
    fun `the toolbar selects the section on screen`() {
        assertEquals(0, docsToolbarSelection(0, 8))
        assertEquals(7, docsToolbarSelection(7, 8))
    }

    /**
     * The bar is built from a fetched guide, so its length is whatever arrived.
     *
     * A page index the bar has no item for is what a stale pager position looks
     * like the moment a shorter guide is fetched under it, and a selection
     * pointing past the end of the list is a crash rather than a wrong
     * highlight.
     */
    @Test
    fun `a page the bar does not have selects nothing`() {
        assertEquals(-1, docsToolbarSelection(8, 8))
        assertEquals(-1, docsToolbarSelection(-1, 8))
        assertEquals(-1, docsToolbarSelection(0, 0))
    }

    @Test
    fun `the toolbar carries every section of the guide, in order`() {
        val pages = listOf(page("START"), page("WIDGET"), page("NEW_ONE"))
        val opened = mutableListOf<Int>()

        val items = docsToolbarItems(pages) { opened += it }

        assertEquals(listOf("START", "WIDGET", "NEW_ONE"), items.map { it.label })
        items.forEach { it.onClick() }
        assertEquals(listOf(0, 1, 2), opened)
    }

    /**
     * A section this build has never heard of still gets a glyph.
     *
     * The guide is fetched, so it can name a page that did not exist when the
     * app was built. That page has to be drawable — the alternative is a bar
     * with a hole in it, or a crash on a lookup that found nothing.
     */
    @Test
    fun `an unknown section falls back to a generic icon`() {
        assertEquals(docsIcon("SOMETHING_NEW"), docsIcon("ANOTHER_NEW_ONE"))
        assertTrue(docsIcon("START") != docsIcon("SOMETHING_NEW"))
    }
}

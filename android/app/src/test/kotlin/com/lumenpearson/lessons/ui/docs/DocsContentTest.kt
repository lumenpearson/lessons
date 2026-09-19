package com.lumenpearson.lessons.ui.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The documentation itself, checked as data.
 *
 * None of this can be checked by reading the screen: an empty page, a list with
 * one point in it or a step numbered 4 after a step numbered 2 all render
 * perfectly well and are all wrong. The content is structured precisely so that
 * these questions have answers, and this is where they are asked.
 */
class DocsContentTest {

    /** Reading order, and the order the toolbar carries them in. */
    @Test
    fun `the sections are the guide in reading order`() {
        assertEquals(
            listOf(
                DocsPage.START,
                DocsPage.TABS,
                DocsPage.WIDGET,
                DocsPage.ALERTS,
                DocsPage.LANGUAGE,
                DocsPage.TELEGRAM,
                DocsPage.DIARY,
                DocsPage.ADMIN,
            ),
            DocsPage.entries,
        )
    }

    @Test
    fun `every section says something`() {
        val empty = DocsPage.entries.filter { it.blocks.isEmpty() }
        assertTrue("Sections with no content: $empty", empty.isEmpty())
    }

    /**
     * A list of one is a paragraph wearing a bullet. Catching it here is
     * cheaper than noticing it on a phone, where a lone point looks deliberate.
     */
    @Test
    fun `no list has fewer than two points`() {
        val thin = DocsPage.entries.flatMap { page ->
            page.blocks.filterIsInstance<DocsBlock.Points>()
                .filter { it.itemsRes.size < 2 }
                .map { page }
        }
        assertTrue("Lists with a single point: $thin", thin.isEmpty())
    }

    /** Steps are read as a sequence, so they have to count like one. */
    @Test
    fun `steps are numbered from one without gaps`() {
        for (page in DocsPage.entries) {
            val numbers = page.blocks.filterIsInstance<DocsBlock.Step>().map { it.number }
            if (numbers.isEmpty()) continue
            assertEquals("Steps on $page", (1..numbers.size).toList(), numbers)
        }
    }

    /**
     * Every string the guide names has to be a real one. A zero here is a
     * resource that was renamed in the XML and left behind in the Kotlin, which
     * compiles and then throws on the page it is on.
     */
    @Test
    fun `no block points at a missing string`() {
        val ids = DocsPage.entries.flatMap { page ->
            listOf(page.titleRes, page.labelRes, page.summaryRes) + page.blocks.flatMap(::idsOf)
        }
        assertTrue("Some resource id is zero", ids.none { it == 0 })
    }

    /**
     * The one place the same sentence twice is a bug rather than a style: a
     * page that quotes another page's paragraph verbatim means one of the two
     * was meant to say something else.
     */
    @Test
    fun `no paragraph is used on two pages`() {
        val seen = mutableMapOf<Int, DocsPage>()
        val repeats = mutableListOf<String>()
        for (page in DocsPage.entries) {
            for (id in page.blocks.flatMap(::idsOf)) {
                val previous = seen.put(id, page)
                if (previous != null) repeats += "$previous and $page share a string"
            }
        }
        assertTrue(repeats.toString(), repeats.isEmpty())
    }

    /** Consecutive steps become one run; everything else stands alone. */
    @Test
    fun `runs gather steps and leave prose alone`() {
        val blocks = listOf(
            DocsBlock.Paragraph(1),
            DocsBlock.Step(1, 2, 3),
            DocsBlock.Step(2, 4, 5),
            DocsBlock.Note(6),
            DocsBlock.Step(1, 7, 8),
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
    fun `runs of a real page keep every block exactly once`() {
        for (page in DocsPage.entries) {
            assertEquals("Blocks of $page", page.blocks, docsRuns(page.blocks).flatten())
        }
    }

    /** The selected item of the toolbar is an index into the list it was handed. */
    @Test
    fun `the toolbar selects the section on screen`() {
        DocsPage.entries.forEachIndexed { index, page ->
            assertEquals(index, docsToolbarSelection(page))
        }
        assertEquals(-1, docsToolbarSelection(null))
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
            DocsBlock.Paragraph(1),
            DocsBlock.Points(listOf(2)),
            DocsBlock.Note(3),
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
        val alone = docsContentType(listOf(DocsBlock.Step(1, 2, 3)))
        val stacked = docsContentType(
            listOf(DocsBlock.Step(1, 2, 3), DocsBlock.Step(2, 4, 5)),
        )

        assertEquals(alone, stacked)
    }

    /** Nothing a real page can produce falls through without a shape. */
    @Test
    fun `every run of every page names a shape`() {
        val known = setOf("steps", "paragraph", "points", "note")
        for (page in DocsPage.entries) {
            for (run in docsRuns(page.blocks)) {
                val type = docsContentType(run)
                assertTrue("$page: unknown content type $type", type in known)
            }
        }
    }

    /** Every string a block names, whatever kind of block it is. */
    private fun idsOf(block: DocsBlock): List<Int> = when (block) {
        is DocsBlock.Paragraph -> listOf(block.textRes)
        is DocsBlock.Note -> listOf(block.textRes)
        is DocsBlock.Points -> block.itemsRes
        is DocsBlock.Step -> listOf(block.titleRes, block.textRes)
    }
}

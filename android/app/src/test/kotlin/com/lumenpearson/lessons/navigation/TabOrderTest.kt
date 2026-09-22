package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.model.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rearranging the bar, and the reader who must not be moved by it.
 *
 * The shell cannot be composed from a JVM test — `HomeShell` is private and
 * builds three view models off `Graph`, which wants DataStore, Room and
 * Retrofit — so what is held here is the half that can be wrong quietly: which
 * way round the permutation goes, and that it is still a permutation
 * afterwards. The gesture that produces it has its own tests in
 * `:core:designsystem` (`ToolbarReorderTest`), in pixels, with no composition
 * either.
 */
class TabOrderTest {

    private val start = listOf(HomeTab.TODAY, HomeTab.WEEK, HomeTab.HOMEWORK)

    /**
     * `moved[0]` is the tab now drawn first, which is the direction the toolbar
     * documents and the opposite of the one that also type-checks.
     */
    @Test
    fun `the tab dragged to the front is the one drawn first`() {
        assertEquals(
            listOf(HomeTab.HOMEWORK, HomeTab.TODAY, HomeTab.WEEK),
            reorderTabs(start, listOf(2, 0, 1)),
        )
    }

    /**
     * The defect this whole change is careful about: `pagerState.currentPage`
     * is an index, and the drag moves what that index means. A reader on
     * «Календарь» who drags «Задания» to the front and is left on page 1 is
     * looking at «Сегодня» without having asked for it.
     */
    @Test
    fun `the reader's tab is somewhere else afterwards, and findable`() {
        val reading = start[1]
        val next = reorderTabs(start, listOf(2, 0, 1))

        assertEquals("the tab moved and the index did not follow", 2, next.indexOf(reading))
        assertNotEquals("keeping the index would have been harmless here", reading, next[1])
    }

    /**
     * Every drag, every page. What is being asserted is that the answer is
     * always a permutation — nothing dropped, nothing doubled — because that is
     * what makes «put the reader back on their tab» a lookup that can succeed
     * at all, whatever the gesture reported.
     */
    @Test
    fun `no drag loses a tab, so the reader can always be put back on theirs`() {
        for (moved in permutations(start.indices.toList())) {
            val next = reorderTabs(start, moved)
            assertEquals("$moved changed how many tabs there are", start.size, next.size)
            assertEquals("$moved lost or doubled a tab", start.toSet(), next.toSet())
            for (reading in start) {
                assertTrue("$moved left $reading off the bar", next.indexOf(reading) >= 0)
            }
        }
    }

    /**
     * A permutation is what the bar promises, not what the shell is entitled to
     * assume: this arrives from a gesture. Storing half of one would take a tab
     * off the bar, and `HomeTab.order` would then append it on the next read —
     * quietly, at the end, which is nowhere the reader left it.
     */
    @Test
    fun `an order that is not a permutation is refused rather than applied`() {
        assertEquals("a repeated index", start, reorderTabs(start, listOf(0, 0, 1)))
        assertEquals("a short list", start, reorderTabs(start, listOf(1, 0)))
        assertEquals("a long one", start, reorderTabs(start, listOf(0, 1, 2, 2)))
        assertEquals("an index off the end", start, reorderTabs(start, listOf(0, 1, 3)))
        assertEquals("nothing at all", start, reorderTabs(start, emptyList()))
    }

    /** A drag that ends where it started leaves the order alone. */
    @Test
    fun `the identity order is the same order`() {
        assertEquals(start, reorderTabs(start, listOf(0, 1, 2)))
    }

    private fun permutations(of: List<Int>): List<List<Int>> =
        if (of.size <= 1) listOf(of)
        else of.flatMap { head -> permutations(of - head).map { listOf(head) + it } }
}

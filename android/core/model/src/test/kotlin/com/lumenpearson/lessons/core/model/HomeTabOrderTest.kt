package com.lumenpearson.lessons.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tab bar's stored order, read back by a build that may not be the one that
 * wrote it.
 *
 * `HomeTab.fromName` has one value to be wrong about and answers with the
 * default; an order has a whole list, and every way it can be wrong ends on the
 * same screen — a bottom bar with a tab missing from it, or one drawn twice,
 * and no way for the reader to tell that anything was dropped. So `order` is
 * written to repair rather than to trust, and each case below is one of the
 * repairs.
 */
class HomeTabOrderTest {

    /** Every arrangement of the bar there is; six for three tabs. */
    private val permutations: List<List<HomeTab>> = permutationsOf(HomeTab.entries)

    // -- nothing stored, or nothing readable -------------------------------

    /**
     * An install that has never touched the setting, one whose preferences file
     * was replaced after corruption, and one that stored a blank all have to
     * open on the bar the app ships with.
     */
    @Test
    fun `nothing stored is the declared order`() {
        assertEquals(HomeTab.entries, HomeTab.order(null))
        assertEquals(HomeTab.entries, HomeTab.order(""))
        assertEquals(HomeTab.entries, HomeTab.order("   "))
        assertEquals(HomeTab.entries, HomeTab.order(",,"))
    }

    /** Whitespace around a name is not a different name. */
    @Test
    fun `padding around the names is ignored`() {
        assertEquals(
            listOf(HomeTab.WEEK, HomeTab.TODAY, HomeTab.HOMEWORK),
            HomeTab.order(" WEEK , TODAY ,HOMEWORK "),
        )
    }

    // -- the two directions a build can differ in --------------------------

    /**
     * A tab that was removed. `SETTINGS` really was one, and `fromName` carries
     * the same case for the default-tab setting.
     */
    @Test
    fun `a name that is not a tab is dropped and the rest survive`() {
        assertEquals(
            listOf(HomeTab.HOMEWORK, HomeTab.TODAY, HomeTab.WEEK),
            HomeTab.order("HOMEWORK,SETTINGS,TODAY,MARKS,WEEK"),
        )
    }

    /**
     * A tab that was added — the shape that matters, and the reason this lives
     * on the companion rather than at a call site.
     *
     * It cannot be written against a fourth member without adding one to the
     * enum, so it is written from the other end: a string holding fewer names
     * than the build has members is exactly what every existing install will
     * hold on the day a tab is added, and the absent ones have to come back at
     * the end rather than be lost. Both halves are asserted — the chosen order
     * is kept in front, and what was missing follows in declaration order, not
     * in whatever order the check happened to find it.
     */
    @Test
    fun `a stored order missing a tab gets it back in declaration order`() {
        assertEquals(
            listOf(HomeTab.HOMEWORK, HomeTab.TODAY, HomeTab.WEEK),
            HomeTab.order("HOMEWORK"),
        )
        assertEquals(
            listOf(HomeTab.HOMEWORK, HomeTab.WEEK, HomeTab.TODAY),
            HomeTab.order("HOMEWORK,WEEK"),
        )
    }

    /** A name written twice is one tab, in the place it was first asked for. */
    @Test
    fun `a duplicate name appears once`() {
        assertEquals(
            listOf(HomeTab.WEEK, HomeTab.TODAY, HomeTab.HOMEWORK),
            HomeTab.order("WEEK,WEEK,TODAY,WEEK"),
        )
    }

    /**
     * The invariant the bar is drawn from, over everything above at once: what
     * comes back is always every tab exactly once. A screen cannot check this
     * for itself, because a tab it never drew leaves nothing behind.
     */
    @Test
    fun `any string at all yields every tab exactly once`() {
        val stored = listOf(
            null, "", " ", ",", "SETTINGS", "TODAY", "today", "TODAY,TODAY",
            "WEEK,SETTINGS,WEEK", "HOMEWORK , WEEK", "TODAY;WEEK", "\n",
        )
        stored.forEach { value ->
            val read = HomeTab.order(value)
            assertEquals("order($value) lost or invented a tab", HomeTab.entries.toSet(), read.toSet())
            assertEquals("order($value) drew a tab twice", HomeTab.entries.size, read.size)
        }
    }

    // -- the round trip ----------------------------------------------------

    /** What was chosen is what is read back, for every arrangement of the bar. */
    @Test
    fun `every permutation round-trips through storage`() {
        assertEquals(6, permutations.size)
        permutations.forEach { permutation ->
            assertEquals(permutation, HomeTab.order(HomeTab.storedOrder(permutation)))
        }
    }

    /** The stored form is the names, so it survives a reordering of the enum. */
    @Test
    fun `the stored form is the member names`() {
        assertEquals(
            "WEEK,HOMEWORK,TODAY",
            HomeTab.storedOrder(listOf(HomeTab.WEEK, HomeTab.HOMEWORK, HomeTab.TODAY)),
        )
        assertEquals("", HomeTab.storedOrder(emptyList()))
    }

    private fun <T> permutationsOf(items: List<T>): List<List<T>> =
        if (items.isEmpty()) {
            listOf(emptyList())
        } else {
            items.flatMap { head ->
                permutationsOf(items - head).map { tail -> listOf(head) + tail }
            }
        }
}

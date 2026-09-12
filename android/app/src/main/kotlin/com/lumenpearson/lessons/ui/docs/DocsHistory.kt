package com.lumenpearson.lessons.ui.docs

import androidx.compose.runtime.Immutable

/**
 * Where the reader has been inside the documentation, newest last.
 *
 * The documentation is the one place in this app where "back" cannot mean "up
 * one layer": every page is a peer of every other, reached from the same bar,
 * so a reader who followed «Виджет» → «Уведомления» → «Управление» has a path
 * that only a history can describe. The settings tree gets away with a depth
 * because its pages form a tree; these do not.
 *
 * Kept as a list rather than a "previous page" field because one step of memory
 * makes back oscillate between the last two pages instead of walking out.
 *
 * @property pages never empty — an empty history is not a state the reader can
 *   be in, and [decode] returns `null` for "the documentation is closed"
 *   instead of inventing one.
 */
@Immutable
class DocsHistory private constructor(val pages: List<DocsPage>) {

    /** The page on screen. */
    val current: DocsPage get() = pages.last()

    /** Whether [back] has anywhere to go that is still inside the documentation. */
    val canGoBack: Boolean get() = pages.size > 1

    /**
     * Opening a page from the toolbar, which is a push and not a replace.
     *
     * Re-opening the page already on screen is a no-op rather than a second
     * entry: a reader who taps the pill they are already on has not navigated,
     * and charging them a press of "back" for it would be a history of their
     * mistakes.
     */
    fun open(page: DocsPage): DocsHistory =
        if (page == current) this else DocsHistory(pages + page)

    /**
     * One step back, or `null` when the next step leaves the documentation.
     *
     * `null` rather than an empty history, so that the single call site cannot
     * forget the case: the two ways out of this screen — the toolbar's button
     * and the system gesture — both run this, and "two ways out that disagree"
     * is the bug this shape rules out.
     */
    fun back(): DocsHistory? =
        if (canGoBack) DocsHistory(pages.dropLast(1)) else null

    /** The whole path as one string; see [decode]. */
    fun encode(): String = pages.joinToString(Separator) { it.name }

    // Two histories are the same when the paths are, which is what a `remember`
    // key and a failing assertion both need; the encoded path is also the only
    // readable thing to put in a test failure.
    override fun equals(other: Any?): Boolean = other is DocsHistory && other.pages == pages

    override fun hashCode(): Int = pages.hashCode()

    override fun toString(): String = "DocsHistory(${encode()})"

    companion object {

        /**
         * Saved state is a `String` because that is what the shell already does
         * with the open settings section, and because a list of enums is not
         * something `rememberSaveable` stores without a saver of its own. One
         * convention, one thing to get wrong.
         */
        private const val Separator = ","

        /** A history that has only just been entered, on [DocsPage.First]. */
        fun opened(page: DocsPage = DocsPage.First): DocsHistory = DocsHistory(listOf(page))

        /**
         * The history [encode] wrote, or `null` for "not in the documentation".
         *
         * Unknown names are dropped rather than refused: the string survives
         * process death, so it can outlive the build that wrote it, and a page
         * that a later version renamed should cost the reader one entry of
         * their path and not the whole screen. A path left with nothing in it
         * reads as closed, which is the only honest answer.
         */
        fun decode(encoded: String?): DocsHistory? {
            if (encoded == null) return null
            val pages = encoded.split(Separator).mapNotNull(DocsPage::fromName)
            return if (pages.isEmpty()) null else DocsHistory(pages)
        }
    }
}

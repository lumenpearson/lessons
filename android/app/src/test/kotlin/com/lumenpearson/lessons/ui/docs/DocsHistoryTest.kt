package com.lumenpearson.lessons.ui.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where "back" goes from every state the documentation can be in.
 *
 * This is the part the owner's instruction is actually about — the toolbar's
 * right-hand button and the system gesture both call [DocsHistory.back] and
 * neither has a rule of its own, so a mistake here is a mistake in both at once
 * and in a way that only shows up on a device. Everything below is the same
 * question asked from a different path.
 */
class DocsHistoryTest {

    @Test
    fun `a freshly opened guide starts on the first section`() {
        val history = DocsHistory.opened()
        assertEquals(DocsPage.First, history.current)
        assertEquals(DocsPage.entries.first(), history.current)
    }

    /** One state deep: back leaves, and says so by returning nothing. */
    @Test
    fun `back from the page the guide opened on leaves the guide`() {
        val history = DocsHistory.opened()
        assertFalse(history.canGoBack)
        assertNull(history.back())
    }

    @Test
    fun `opening a section from the toolbar is a push`() {
        val history = DocsHistory.opened().open(DocsPage.WIDGET)
        assertEquals(DocsPage.WIDGET, history.current)
        assertTrue(history.canGoBack)
    }

    /** Three taps in, three presses of back walk out the way they came in. */
    @Test
    fun `back retraces the path one section at a time`() {
        val history = DocsHistory.opened(DocsPage.START)
            .open(DocsPage.WIDGET)
            .open(DocsPage.ALERTS)
            .open(DocsPage.ADMIN)

        val first = requireNotNull(history.back())
        assertEquals(DocsPage.ALERTS, first.current)

        val second = requireNotNull(first.back())
        assertEquals(DocsPage.WIDGET, second.current)

        val third = requireNotNull(second.back())
        assertEquals(DocsPage.START, third.current)

        assertNull(third.back())
    }

    /**
     * The path is a path, not a set. Somebody who reads about the widget, goes
     * to the notifications and comes back to the widget has been in three
     * places, and back has to unwind all three.
     */
    @Test
    fun `a section visited twice is two entries`() {
        val history = DocsHistory.opened(DocsPage.WIDGET)
            .open(DocsPage.ALERTS)
            .open(DocsPage.WIDGET)

        assertEquals(
            listOf(DocsPage.WIDGET, DocsPage.ALERTS, DocsPage.WIDGET),
            history.pages,
        )
    }

    /** Tapping the pill you are already standing on is not navigation. */
    @Test
    fun `re-opening the current section does not grow the path`() {
        val history = DocsHistory.opened(DocsPage.DIARY)
        assertEquals(history, history.open(DocsPage.DIARY))
        assertNull(history.open(DocsPage.DIARY).back())
    }

    @Test
    fun `a path survives being written out and read back`() {
        val history = DocsHistory.opened(DocsPage.START)
            .open(DocsPage.TELEGRAM)
            .open(DocsPage.LANGUAGE)

        assertEquals(history, DocsHistory.decode(history.encode()))
    }

    /** `null` is how the shell says the documentation is closed. */
    @Test
    fun `nothing saved means the guide is not open`() {
        assertNull(DocsHistory.decode(null))
    }

    /**
     * A path written by an older build can name a section this one no longer
     * has. Losing that entry costs one press of back; refusing the whole string
     * would drop the reader out of a screen they are looking at.
     */
    @Test
    fun `an unknown section is dropped from a restored path`() {
        val restored = DocsHistory.decode("START,WHAT_THIS_WAS_CALLED_LAST_YEAR,WIDGET")
        assertEquals(listOf(DocsPage.START, DocsPage.WIDGET), restored?.pages)
    }

    @Test
    fun `a path with nothing left in it reads as closed`() {
        assertNull(DocsHistory.decode(""))
        assertNull(DocsHistory.decode("NOT_A_PAGE"))
    }
}

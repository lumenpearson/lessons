package com.lumenpearson.lessons.core.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first line of a bug report becomes the issue title, and the issue is
 * public. Whatever this cuts, it must not produce a character that is not one.
 */
class BugReportComposerTest {

    /** One under the 72-character limit, so the ellipsis fits inside it. */
    private val cut = 71

    @Test
    fun `a short line is left exactly as it is`() {
        val line = "Виджет показывает вчерашний день"

        assertEquals(line, BugReportComposer.shorten(line))
    }

    /**
     * `take` counts UTF-16 code units, not characters, so a cut landing between
     * the halves of a surrogate pair left the leading half in the title — which
     * is not a character at all, and renders as a replacement box wherever
     * GitHub lists the issue.
     */
    @Test
    fun `a cut through an emoji takes the whole emoji, not half of it`() {
        // The pair straddles the boundary: its high half is the last unit kept.
        val line = "а".repeat(cut - 1) + "😀" + "а".repeat(20)

        val title = BugReportComposer.shorten(line)

        assertFalse("a lone high surrogate survived the cut", title.any { it.isHighSurrogate() })
        assertEquals("а".repeat(cut - 1) + "…", title)
    }

    /** The pair sitting wholly inside the budget is kept, not defensively dropped. */
    @Test
    fun `an emoji that fits is kept`() {
        val line = "а".repeat(cut - 2) + "😀" + "а".repeat(20)

        val title = BugReportComposer.shorten(line)

        assertTrue(title.startsWith("а".repeat(cut - 2) + "😀"))
        assertEquals("а".repeat(cut - 2) + "😀" + "…", title)
    }

    @Test
    fun `a long plain line is cut to the limit and ellipsised`() {
        val title = BugReportComposer.shorten("а".repeat(200))

        assertEquals("а".repeat(cut) + "…", title)
    }

    /** Trailing space before the ellipsis reads as a typo, not as a truncation. */
    @Test
    fun `the space a cut lands on is trimmed away`() {
        val line = "а".repeat(cut - 1) + " " + "б".repeat(20)

        assertEquals("а".repeat(cut - 1) + "…", BugReportComposer.shorten(line))
    }
}

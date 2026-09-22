package com.lumenpearson.lessons.widget.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-line clip every outside string on the widget goes through.
 *
 * Glance's `Text` cannot ellipsize, so the truncation is in the string — and
 * what it has to survive is whatever somebody typed into Telegram, which is
 * stored with its ends trimmed and its middle intact.
 */
class EllipsizeTest {

    @Test
    fun `a title typed on two lines is drawn on one, and says it was cut`() {
        // The defect this closes: a Glance `Text` at `maxLines = 1` hard-clips
        // at the first newline, so the widget drew «Собрание» and stopped —
        // with the «…» this function had appended sitting after a break that
        // was never drawn. The same event in the app showed «Собрание…»,
        // because Compose ellipsises what it truncates. The home screen was the
        // one surface that cut silently.
        val typed = "Собрание\nв актовом зале"

        val drawn = typed.ellipsize(19)

        assertTrue("the widget would clip at the newline and say nothing", '\n' !in drawn)
        assertEquals("Собрание в актовом…", drawn)
    }

    @Test
    fun `a string that fits still comes back flat`() {
        // The early return is the half that is easy to get wrong: a short
        // string is handed straight back, so collapsing after that check would
        // leave exactly the strings that need no cutting carrying their breaks.
        assertEquals("Собрание в зале", "Собрание\nв зале".ellipsize(100))
        assertEquals("Алгебра", "  Алгебра  ".ellipsize(100))
    }

    @Test
    fun `a run of spaces costs one character, not five`() {
        // Budgets on this widget are counted in characters, and a teacher's
        // double space or tab spent them on nothing visible.
        assertEquals("Контрольная работа", "Контрольная    работа".ellipsize(100))
    }

    @Test
    fun `an emoji is never cut in half`() {
        // A `Char` is a UTF-16 code unit, so a cut can land between the halves
        // of a surrogate pair — and the leading half is not whitespace, so
        // `trimEnd` keeps it and the widget draws a tofu box.
        val cut = "Урок 🎓🎓🎓".ellipsize(8)

        assertTrue(
            "a lone surrogate survived the cut: $cut",
            cut.none { it.isHighSurrogate() && cut.indexOf(it) == cut.length - 2 } ||
                cut.dropLast(1).last().isLowSurrogate(),
        )
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun `nothing is added to a string there was no room to cut`() {
        // `maxChars <= 1` leaves no room for the ellipsis itself, so the string
        // comes back as it was rather than as a lone «…».
        assertEquals("Алгебра", "Алгебра".ellipsize(1))
        assertEquals("Алгебра", "Алгебра".ellipsize(0))
    }
}

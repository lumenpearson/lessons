package com.lumenpearson.lessons.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping behind the session sheet.
 *
 * Two rules carry the feature and neither is obvious from the screen. A second
 * pass over a string replaces the first rather than adding to it, or the export
 * would ask a reviewer to apply two versions of one line; and a correction that
 * ends up saying what the string already said is not recorded at all, which is
 * how a reader undoes a change without being told there is an undo.
 */
class TranslationSessionTest {

    private val russian = TranslationEdit("week_title", "ru", "Неделя", "Неделя целиком")
    private val english = TranslationEdit("week_title", "en", "Week", "The whole week")

    @Test
    fun `a new session holds nothing`() {
        val session = TranslationSession()
        assertTrue(session.isEmpty)
        assertEquals(0, session.size)
        assertNull(session.correctionOf("week_title", "ru"))
    }

    @Test
    fun `an edit is recorded and read back`() {
        val session = TranslationSession().updated(russian)
        assertEquals(listOf(russian), session.edits)
        assertEquals("Неделя целиком", session.correctionOf("week_title", "ru"))
    }

    /** The same key in two languages is two corrections, not one overwriting the other. */
    @Test
    fun `the locale is part of a correction's identity`() {
        val session = TranslationSession().updated(russian).updated(english)
        assertEquals(2, session.size)
        assertEquals("Неделя целиком", session.correctionOf("week_title", "ru"))
        assertEquals("The whole week", session.correctionOf("week_title", "en"))
    }

    /** A second pass replaces the first, and keeps the place it had in the list. */
    @Test
    fun `editing the same string twice replaces it in place`() {
        val other = TranslationEdit("today_title", "ru", "Сегодня", "Сегодняшние уроки")
        val session = TranslationSession()
            .updated(russian)
            .updated(other)
            .updated(russian.copy(corrected = "Вся неделя"))

        assertEquals(2, session.size)
        assertEquals("Вся неделя", session.correctionOf("week_title", "ru"))
        assertEquals(listOf("week_title", "today_title"), session.edits.map { it.key })
    }

    @Test
    fun `an edit that restores the original is not an edit`() {
        val session = TranslationSession()
            .updated(russian)
            .updated(russian.copy(corrected = "Неделя"))
        assertTrue(session.isEmpty)
    }

    /** Same rule, and the reason the comparison ignores the ends. */
    @Test
    fun `a correction that only adds surrounding space is not an edit`() {
        val session = TranslationSession().updated(russian.copy(corrected = "  Неделя "))
        assertTrue(session.isEmpty)
    }

    /** Space the reader put inside the value is theirs and is kept verbatim. */
    @Test
    fun `a real correction keeps the whitespace it was typed with`() {
        val session = TranslationSession().updated(russian.copy(corrected = " Неделя целиком "))
        assertEquals(" Неделя целиком ", session.correctionOf("week_title", "ru"))
    }

    @Test
    fun `a blank correction is not an edit`() {
        val session = TranslationSession()
            .updated(russian)
            .updated(russian.copy(corrected = "   "))
        assertTrue(session.isEmpty)
    }

    @Test
    fun `one correction can be dropped without touching the others`() {
        val session = TranslationSession()
            .updated(russian)
            .updated(english)
            .without("week_title", "ru")

        assertEquals(listOf(english), session.edits)
        assertNull(session.correctionOf("week_title", "ru"))
    }

    @Test
    fun `dropping something that was never corrected changes nothing`() {
        val session = TranslationSession().updated(russian)
        assertEquals(session, session.without("today_title", "ru"))
    }
}

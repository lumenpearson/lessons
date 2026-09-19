package com.lumenpearson.lessons.ui.translate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The rules that turn a long press back into a string.
 *
 * Correction mode draws its outline around a `Text`, and a `Text` has words,
 * not a resource id: the rows and cards this app is built from take a title,
 * because half of those titles are a subject out of the database. So the way
 * back is a registry of what is on screen — and the interesting cases are all
 * about what it is allowed to answer when it is not sure.
 *
 * Looking the words up in `values/` afterwards instead is what this replaces,
 * and the numbers say why it had to: 204 of this app's 991 strings share their
 * text with another string, and 100 more are format patterns that match none
 * of the sentences they produce. A lookup would have quietly exported a fix
 * for `docs_back` when the reader corrected `action_back`.
 */
class AppCorrectionsTest {

    /**
     * Ids are arbitrary here, as they are in a build: `R.string.x` is whatever
     * aapt assigned. What matters is that the table maps them consistently.
     */
    private val table = FakeStringTable(
        mapOf(
            1 to ("action_back" to "Назад"),
            2 to ("docs_back" to "Назад"),
            3 to ("homework_title" to "Задания"),
            4 to ("update_size_mb" to "%1\$s МБ"),
        ),
    )

    private fun corrections() = AppCorrections(table, locale = "ru")

    @Before
    fun on() {
        TranslationMode.clear()
        TranslationMode.enabled = true
    }

    @After
    fun off() {
        TranslationMode.enabled = false
        TranslationMode.clear()
    }

    @Test
    fun `text nobody registered belongs to nobody`() {
        val corrections = corrections()
        corrections.noteOnScreen(3, "Задания")
        // A subject, a teacher, a homework note: the words are on the screen,
        // they are not the app's own copy, and offering to correct them would
        // offer to correct somebody's database.
        assertEquals(emptyList<Int>(), corrections.keysBehind("Алгебра"))
    }

    @Test
    fun `a string that has left the screen stops answering for its words`() {
        val corrections = corrections()
        corrections.noteOnScreen(3, "Задания")
        corrections.forget(3, "Задания")
        assertEquals(emptyList<Int>(), corrections.keysBehind("Задания"))
    }

    @Test
    fun `the same string drawn twice survives one of them leaving`() {
        // The registry has to count rather than remember: a title repeated in a
        // header and in a card is one key and two texts, and the card scrolling
        // away must not take the header's outline with it.
        val corrections = corrections()
        corrections.noteOnScreen(3, "Задания")
        corrections.noteOnScreen(3, "Задания")
        corrections.forget(3, "Задания")
        assertEquals(listOf(3), corrections.keysBehind("Задания"))
    }

    @Test
    fun `two strings with the same words are both offered`() {
        val corrections = corrections()
        corrections.noteOnScreen(1, "Назад")
        corrections.noteOnScreen(2, "Назад")
        assertEquals(listOf(1, 2), corrections.keysBehind("Назад"))

        corrections.edit(corrections.keysBehind("Назад"))
        assertEquals(listOf("action_back", "docs_back"), corrections.editing.map { it.key })
    }

    @Test
    fun `the editor never opens on two different originals at once`() {
        // Reachable: correct `action_back` to "Назад" while `docs_back` already
        // says it, and both are now drawn with the same words. One field cannot
        // honestly stand for two different lines of `values/`, so the one the
        // reader pressed wins and the other is left alone.
        TranslationMode.record(
            key = "homework_title",
            locale = "ru",
            original = "Задания",
            corrected = "Назад",
        )
        val corrections = corrections()
        corrections.edit(listOf(3, 1))
        assertEquals(listOf("homework_title"), corrections.editing.map { it.key })
        assertEquals("Задания", corrections.editing.single().original)
    }

    @Test
    fun `a correction is shown in place of the string it ships with`() {
        TranslationMode.record(
            key = "homework_title",
            locale = "ru",
            original = "Задания",
            corrected = "Домашка",
        )
        val corrections = corrections()
        assertEquals("Домашка", corrections.correctionOf(3, "Задания"))
        // And only in its own language: the same session opened in English is
        // looking at a different file.
        assertEquals("Задания", AppCorrections(table, locale = "en").correctionOf(3, "Задания"))
    }

    @Test
    fun `the editor is opened on the pattern, not on the sentence it drew`() {
        // What is registered is "12,4 МБ", because that is what a finger can
        // land on. What has to be exported is `%1$s МБ`, because that is what
        // is written in `values/`.
        val corrections = corrections()
        corrections.noteOnScreen(4, "12,4 МБ")
        corrections.edit(corrections.keysBehind("12,4 МБ"))
        assertEquals("%1\$s МБ", corrections.editing.single().original)
    }

    @Test
    fun `a string with no exportable name is never registered`() {
        // A plural, an id from another package, an id that no longer resolves.
        // Kept out here rather than filtered on the way back, so that a long
        // press on one does nothing instead of opening an editor over a key
        // that cannot be written into `values/`.
        val corrections = corrections()
        corrections.noteOnScreen(99, "Три урока")
        assertEquals(emptyList<Int>(), corrections.keysBehind("Три урока"))
        corrections.edit(listOf(99))
        assertTrue(corrections.editing.isEmpty())
    }

    @Test
    fun `nothing is on offer while the mode is off`() {
        TranslationMode.enabled = false
        assertTrue(!corrections().enabled)
    }

    @Test
    fun `but the corrections themselves survive the mode being switched off`() {
        // Switching the mode off is how a reader takes the outlines away and
        // reads the app in their own wording to see whether it still fits the
        // rows. Reverting every correction at that moment would take away the
        // only way to check them, while «Исправления» went on listing them.
        TranslationMode.record(
            key = "homework_title",
            locale = "ru",
            original = "Задания",
            corrected = "Домашка",
        )
        TranslationMode.enabled = false
        assertEquals("Домашка", corrections().correctionOf(3, "Задания"))
    }

    @Test
    fun `a session nobody has started costs one read and no lookup`() {
        // Asked of every string in the app on every composition, including in
        // the build everybody ships. Counting the table calls is the only way
        // to see the difference between "returns the same string" and "does
        // no work to return it".
        TranslationMode.clear()
        val corrections = corrections()
        repeat(5) { corrections.correctionOf(3, "Задания") }
        assertEquals(0, table.nameLookups)
    }

    private class FakeStringTable(private val rows: Map<Int, Pair<String, String>>) : StringTable {
        var nameLookups = 0
            private set

        override fun nameOf(id: Int): String? {
            nameLookups++
            return rows[id]?.first
        }

        override fun textOf(id: Int): String = rows.getValue(id).second
    }
}

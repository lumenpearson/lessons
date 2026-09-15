package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.repository.DiaryEdit
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the correction sheet is handed for one row.
 *
 * The thing worth pinning is the pair of values every field has. What is shown
 * is already corrected — the server lays corrections over on the way out — so
 * the diary's own answer survives only inside the edit, and that is what a save
 * compares against to decide between writing a correction and taking one off.
 * Get that backwards and typing a field back to what the school wrote stores a
 * correction saying "show exactly what you were going to show anyway", which
 * leaves the row marked as corrected for good.
 */
class DiaryCorrectionsTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private fun lesson(
        room: String? = null,
        teacher: String? = null,
        homework: String? = null,
        topic: String? = null,
        edits: List<DiaryEdit> = emptyList(),
        ambiguous: Boolean = false,
    ) = DiaryLesson(
        date = monday,
        number = 1,
        subject = "Алгебра",
        startsAt = null,
        endsAt = null,
        room = room,
        teacher = teacher,
        homework = homework,
        topic = topic,
        target = "lesson:2026-09-07:n1:Алгебра",
        edits = edits,
        ambiguous = ambiguous,
    )

    @Test
    fun `an untouched field says the diary is the source of what is shown`() {
        val corrections = lesson(room = "12").corrections()

        assertEquals("12", corrections.values[DiaryField.ROOM])
        assertEquals("12", corrections.upstreamOf(DiaryField.ROOM))
        assertFalse(corrections.hasCorrections)
    }

    @Test
    fun `a corrected field shows the correction and keeps the diary underneath`() {
        val corrections = lesson(
            room = "204",
            edits = listOf(DiaryEdit("room", "204", original = "12", changedUpstream = false)),
        ).corrections()

        assertEquals("204", corrections.values[DiaryField.ROOM])
        assertEquals("12", corrections.upstreamOf(DiaryField.ROOM))
        assertTrue(corrections.hasCorrections)
        assertEquals(setOf(DiaryField.ROOM), corrections.corrected)
    }

    @Test
    fun `a field the diary has since changed is flagged`() {
        val corrections = lesson(
            teacher = "Иванова И. И.",
            edits = listOf(
                DiaryEdit("teacher", "Иванова И. И.", original = "Петрова", changedUpstream = true),
            ),
        ).corrections()

        assertEquals(setOf(DiaryField.TEACHER), corrections.changedUpstream)
        assertEquals("Петрова", corrections.upstreamOf(DiaryField.TEACHER))
    }

    @Test
    fun `a lesson does not offer to correct homework it never draws`() {
        // The day card draws the homework list under the lesson, from the other
        // endpoint and under a different key. Offering it twice would leave one
        // copy corrected and the other showing the diary's own text.
        assertFalse(DiaryField.HOMEWORK in lesson().corrections().fields)
    }

    @Test
    fun `a field the diary never filled in reads as nothing, not as empty`() {
        // The two have to agree: an edit reports "the diary said nothing" as
        // null, so an untouched blank field must too, or the comparison that
        // decides between a correction and a reset reads them differently.
        val corrections = lesson().corrections()

        assertEquals("", corrections.values[DiaryField.TOPIC])
        assertNull(corrections.upstreamOf(DiaryField.TOPIC))
    }

    @Test
    fun `a correction over a field the diary left blank keeps the blank as nothing`() {
        val corrections = lesson(
            room = "204",
            edits = listOf(DiaryEdit("room", "204", original = "", changedUpstream = false)),
        ).corrections()

        assertNull(corrections.upstreamOf(DiaryField.ROOM))
    }

    @Test
    fun `a lesson offers its fields in the order they read on screen`() {
        assertEquals(
            listOf(DiaryField.ROOM, DiaryField.TEACHER, DiaryField.TOPIC),
            lesson().corrections().fields,
        )
    }

    @Test
    fun `a lesson the server could not tell apart carries that through`() {
        assertTrue(lesson(ambiguous = true).corrections().ambiguous)
    }

    @Test
    fun `an edit for a field this build does not know is dropped`() {
        // Not defaulted to something: a correction with nothing to be drawn
        // next to would get a reset button that finds nothing.
        val corrections = lesson(
            edits = listOf(DiaryEdit("invented", "x", original = null, changedUpstream = false)),
        ).corrections()

        assertFalse(corrections.hasCorrections)
        assertEquals(3, corrections.fields.size)
    }

    @Test
    fun `homework offers its one field and keeps the target it was given`() {
        val item = DiaryHomework(
            id = 77,
            dueDate = monday,
            subject = "Алгебра",
            text = "§ 5, упр. 3",
            teacher = null,
            target = "hw:id:77",
            edits = listOf(DiaryEdit("text", "§ 5, упр. 3", original = "§ 5", changedUpstream = false)),
        )

        val corrections = item.corrections()

        assertEquals(listOf(DiaryField.TEXT), corrections.fields)
        assertEquals("hw:id:77", corrections.target)
        assertEquals("§ 5", corrections.upstreamOf(DiaryField.TEXT))
        assertFalse(corrections.ambiguous)
    }

    @Test
    fun `homework the server could not tell apart carries that through too`() {
        // Two assignments in one subject due the same day, neither with an
        // upstream id, key the same — and the sheet refuses to write on a row
        // it cannot name. This flag was hardcoded false on the homework path
        // for a while, which meant the sheet happily offered a save whose
        // correction the server would then decline to apply to anything.
        val item = DiaryHomework(
            id = null,
            dueDate = monday,
            subject = "Алгебра",
            text = "§ 5, упр. 3",
            teacher = null,
            target = "hw:2026-09-07:Алгебра",
            ambiguous = true,
        )

        assertTrue(item.corrections().ambiguous)
    }
}

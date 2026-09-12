package com.lumenpearson.lessons.widget.ui

import com.lumenpearson.lessons.core.model.HomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The number beside the word «предмет» has to be a number of subjects.
 *
 * Two places count it — the homework block's own summary and the one-line
 * "ДЗ на сегодня" under the timeline — and they counted different things, so a
 * teacher who filed two pieces of homework for one lesson made the widget
 * contradict itself on the same home screen.
 */
class HomeworkSubjectsTest {

    private fun homework(subject: String, text: String = "№ 42") =
        HomeworkItem(subject = subject, text = text)

    @Test
    fun `two entries for one subject are one subject`() {
        val items = listOf(homework("Алгебра", "№ 42"), homework("Алгебра", "прочитать § 5"))

        assertEquals(1, subjectsIn(items))
    }

    @Test
    fun `distinct subjects are counted once each`() {
        val items = listOf(homework("Алгебра"), homework("Физика"), homework("Алгебра"))

        assertEquals(2, subjectsIn(items))
    }

    /** An entry with no text is a subject with nothing set, not homework. */
    @Test
    fun `a blank entry does not count as a subject`() {
        assertEquals(0, subjectsIn(listOf(homework("Алгебра", "   "))))
        assertEquals(1, subjectsIn(listOf(homework("Алгебра", "   "), homework("Физика"))))
    }

    @Test
    fun `nothing set is nothing`() {
        assertEquals(0, subjectsIn(emptyList()))
    }
}

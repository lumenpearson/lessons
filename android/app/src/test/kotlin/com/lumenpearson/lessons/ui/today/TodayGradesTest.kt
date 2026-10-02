package com.lumenpearson.lessons.ui.today

import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.model.Lesson
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a diary mark finds its row on «Сегодня» (#219). */
class TodayGradesTest {

    private val today = LocalDate.of(2026, 10, 2)

    private val algebra = lesson(1, "Алгебра", 9)
    private val pe = lesson(2, "Физическая Культура", 10)
    private val algebraAgain = lesson(3, "Алгебра", 11)
    private val day = listOf(algebra, pe, algebraAgain)

    @Test
    fun `a mark is pinned by date and by the subject's folded name`() {
        val grades = gradesByLesson(
            lessons = day,
            marks = listOf(mark("физическая  культура", "5")),
            date = today,
        )
        assertEquals(mapOf(pe to listOf("5")), grades)
    }

    @Test
    fun `a subject taught twice gets its marks on the first of its lessons`() {
        val grades = gradesByLesson(day, listOf(mark("Алгебра", "4"), mark("Алгебра", "5")), today)
        assertEquals(mapOf(algebra to listOf("4", "5")), grades)
    }

    @Test
    fun `another day's mark, an absence and an unknown subject draw nothing`() {
        val grades = gradesByLesson(
            lessons = day,
            marks = listOf(
                mark("Алгебра", "5", date = today.minusDays(1)),
                mark("Алгебра", "Н", kind = DiaryMarkKind.ABSENCE),
                mark("Химия", "5"),
            ),
            date = today,
        )
        assertEquals(emptyMap<Lesson, List<String>>(), grades)
    }

    @Test
    fun `a cancelled lesson does not take its subject's mark`() {
        val cancelled = algebra.copy(isCancelled = true)
        val grades = gradesByLesson(listOf(cancelled, pe, algebraAgain), listOf(mark("Алгебра", "3")), today)
        assertEquals(mapOf(algebraAgain to listOf("3")), grades)
    }

    private fun lesson(index: Int, subject: String, hour: Int) = Lesson(
        index = index,
        subject = subject,
        startsAt = LocalTime.of(hour, 0),
        endsAt = LocalTime.of(hour, 40),
    )

    private fun mark(
        subject: String,
        value: String,
        date: LocalDate = today,
        kind: DiaryMarkKind = DiaryMarkKind.GRADE,
    ) = DiaryMark(
        id = null,
        subjectId = null,
        subject = subject,
        date = date,
        value = value,
        kind = kind,
        reason = null,
        comment = null,
    )
}

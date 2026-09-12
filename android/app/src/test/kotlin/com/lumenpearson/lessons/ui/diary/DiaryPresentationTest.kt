package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the diary screens make, tested where they live rather than
 * through a composable that would need a device to run.
 *
 * Three of them matter enough to be here for good: an absence is not a mark and
 * must not be averaged as one, a week is seven days of which only the ones with
 * something on them are drawn, and the marks window has to fit inside the 62
 * days the server will serve however long the school's term is.
 */
class DiaryPresentationTest {

    private val monday = LocalDate.of(2026, 9, 14)

    // -- what a register entry is ------------------------------------------

    @Test
    fun `every kind of entry has its own name on screen`() {
        assertEquals(R.string.diary_kind_grade, DiaryMarkKind.GRADE.labelRes())
        assertEquals(R.string.diary_kind_absence, DiaryMarkKind.ABSENCE.labelRes())
        assertEquals(R.string.diary_kind_late, DiaryMarkKind.LATE.labelRes())
        assertEquals(R.string.diary_kind_remark, DiaryMarkKind.REMARK.labelRes())
        assertEquals(R.string.diary_kind_other, DiaryMarkKind.OTHER.labelRes())
    }

    /** A mark and an absence must never be drawn in the same colour. */
    @Test
    fun `a mark and an absence are told apart by hue`() {
        val tones = DiaryMarkKind.entries.map { it.toneIndex() }
        assertEquals(tones.size, tones.toSet().size)
    }

    @Test
    fun `only a grade carries a number`() {
        assertEquals(5, mark("5", DiaryMarkKind.GRADE).numericValue)
        // "Н" is an absence whose *value* happens to be a letter; a grade-kind
        // entry whose value is not a number is just as unaveragable.
        assertNull(mark("Н", DiaryMarkKind.ABSENCE).numericValue)
        assertNull(mark("5", DiaryMarkKind.ABSENCE).numericValue)
        assertNull(mark("5-", DiaryMarkKind.GRADE).numericValue)
        assertNull(mark("зачёт", DiaryMarkKind.GRADE).numericValue)
    }

    // -- the average --------------------------------------------------------

    @Test
    fun `the average is over the marks that are numbers`() {
        val summary = summariseMarks(
            listOf(
                mark("5", DiaryMarkKind.GRADE, subject = "Алгебра"),
                mark("4", DiaryMarkKind.GRADE, subject = "Алгебра"),
                mark("Н", DiaryMarkKind.ABSENCE, subject = "Алгебра"),
                mark("Оп", DiaryMarkKind.LATE, subject = "Алгебра"),
                mark("5-", DiaryMarkKind.GRADE, subject = "Алгебра"),
            ),
        ).single()

        assertEquals(4.5, summary.average!!, 0.0001)
        // The two that are not marks are still shown - as what they are.
        assertEquals(2, summary.notes.size)
        assertEquals(3, summary.grades.size)
    }

    @Test
    fun `a subject with nothing numeric has no average rather than a zero`() {
        val summary = summariseMarks(
            listOf(
                mark("Н", DiaryMarkKind.ABSENCE, subject = "Физика"),
                mark("зачёт", DiaryMarkKind.GRADE, subject = "Физика"),
            ),
        ).single()

        assertNull(summary.average)
    }

    @Test
    fun `subjects are grouped, named once and ordered by name`() {
        val summaries = summariseMarks(
            listOf(
                mark("4", DiaryMarkKind.GRADE, subject = "Физика"),
                mark("5", DiaryMarkKind.GRADE, subject = "Алгебра"),
                mark("3", DiaryMarkKind.GRADE, subject = "Физика"),
                mark("2", DiaryMarkKind.GRADE, subject = "   "),
            ),
        )

        assertEquals(listOf("Алгебра", "Физика"), summaries.map { it.subject })
        assertEquals(3.5, summaries[1].average!!, 0.0001)
    }

    // -- the week -----------------------------------------------------------

    @Test
    fun `a week groups lessons and the homework due with them`() {
        val week = diaryWeek(
            weekStart = monday,
            lessons = listOf(
                lesson(monday, number = 2, at = LocalTime.of(9, 25)),
                lesson(monday, number = 1, at = LocalTime.of(8, 30), subject = "Физика"),
                lesson(monday.plusDays(2), number = 1, at = LocalTime.of(8, 30)),
            ),
            homework = listOf(
                DiaryHomework(
                    id = 1,
                    dueDate = monday.plusDays(2),
                    subject = "Алгебра",
                    text = "§12",
                    teacher = null,
                ),
            ),
        )

        assertEquals(listOf(monday, monday.plusDays(2)), week.map { it.date })
        // Sorted by the bell, not by the order the server happened to send.
        assertEquals(listOf("Физика", "Алгебра"), week.first().lessons.map { it.subject })
        assertTrue(week.first().homework.isEmpty())
        assertEquals("§12", week[1].homework.single().text)
    }

    /** A day with homework and no lessons is still a day worth drawing. */
    @Test
    fun `homework alone keeps a day in the week`() {
        val week = diaryWeek(
            weekStart = monday,
            lessons = emptyList(),
            homework = listOf(
                DiaryHomework(
                    id = null,
                    dueDate = monday.plusDays(4),
                    subject = "Химия",
                    text = "Опыт",
                    teacher = null,
                ),
            ),
        )

        assertEquals(listOf(monday.plusDays(4)), week.map { it.date })
    }

    /** Nothing from outside the seven days, however the server answered. */
    @Test
    fun `dates outside the week are dropped`() {
        val week = diaryWeek(
            weekStart = monday,
            lessons = listOf(
                lesson(monday.minusDays(1), number = 1, at = LocalTime.of(8, 30)),
                lesson(monday.plusDays(7), number = 1, at = LocalTime.of(8, 30)),
            ),
            homework = emptyList(),
        )

        assertTrue(week.isEmpty())
    }

    @Test
    fun `a lesson with no time sorts after the ones that have one`() {
        val week = diaryWeek(
            weekStart = monday,
            lessons = listOf(
                lesson(monday, number = null, at = null, subject = "Классный час"),
                lesson(monday, number = 1, at = LocalTime.of(8, 30), subject = "Алгебра"),
            ),
            homework = emptyList(),
        )

        assertEquals(listOf("Алгебра", "Классный час"), week.single().lessons.map { it.subject })
    }

    @Test
    fun `a week starts on the Monday of whatever day it is given`() {
        assertEquals(monday, diaryWeekStart(monday.plusDays(6)))
        assertEquals(monday, diaryWeekStart(monday))
    }

    // -- the marks window ---------------------------------------------------

    @Test
    fun `a term that fits is asked for whole`() {
        val today = LocalDate.of(2026, 9, 20)
        val range = diaryGradeRange(
            today = today,
            period = period(starts = LocalDate.of(2026, 9, 1), ends = LocalDate.of(2026, 10, 25)),
        )

        assertEquals(LocalDate.of(2026, 9, 1), range.from)
        // Never past today: marks that have not been given yet say nothing.
        assertEquals(today, range.to)
    }

    /** A quarter is routinely longer than the 62 days one request may cover. */
    @Test
    fun `a term too long for one request keeps its most recent days`() {
        val today = LocalDate.of(2026, 12, 20)
        val range = diaryGradeRange(
            today = today,
            period = period(starts = LocalDate.of(2026, 9, 1), ends = LocalDate.of(2026, 12, 30)),
        )

        assertEquals(today, range.to)
        assertEquals(62L, range.to.toEpochDay() - range.from.toEpochDay())
    }

    @Test
    fun `a finished term ends where it ended`() {
        val range = diaryGradeRange(
            today = LocalDate.of(2026, 12, 20),
            period = period(starts = LocalDate.of(2026, 9, 1), ends = LocalDate.of(2026, 10, 25)),
        )

        assertEquals(LocalDate.of(2026, 10, 25), range.to)
        assertEquals(LocalDate.of(2026, 9, 1), range.from)
    }

    @Test
    fun `with no term at all it is the last 62 days`() {
        val today = LocalDate.of(2026, 9, 20)
        val range = diaryGradeRange(today = today, period = null)

        assertEquals(today, range.to)
        assertEquals(today.minusDays(62), range.from)
    }

    private fun mark(
        value: String,
        kind: DiaryMarkKind,
        subject: String = "Алгебра",
    ) = DiaryMark(
        id = null,
        subjectId = null,
        subject = subject,
        date = LocalDate.of(2026, 9, 14),
        value = value,
        kind = kind,
        reason = null,
        comment = null,
    )

    private fun lesson(
        date: LocalDate,
        number: Int?,
        at: LocalTime?,
        subject: String = "Алгебра",
    ) = DiaryLesson(
        date = date,
        number = number,
        subject = subject,
        startsAt = at,
        endsAt = at?.plusMinutes(45),
        room = null,
        teacher = null,
        homework = null,
        topic = null,
    )

    private fun period(starts: LocalDate, ends: LocalDate) = DiaryPeriod(
        id = 1,
        name = "1 четверть",
        startsOn = starts,
        endsOn = ends,
        isCurrent = true,
    )
}

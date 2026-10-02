package com.lumenpearson.lessons.widget.ui

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.widget.WidgetSizeClass
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After school, what fills a tall widget under the homework (#221).
 *
 * The defect was a tall widget with «Уроки закончились», a plan line and
 * «Ничего не задано», and nothing under them — the after-school layout had no
 * block that grew with the height.
 */
class RestDayLessonsTest {

    private val tomorrow = SchoolDay(
        date = LocalDate.of(2026, 10, 5),
        weekday = 1,
        kind = DayKind.NORMAL,
        lessons = (1..7).map { index ->
            Lesson(
                index = index,
                subject = "Предмет $index",
                startsAt = LocalTime.of(8 + index, 0),
                endsAt = LocalTime.of(8 + index, 40),
            )
        },
    )

    private val nothingSet = HomeworkPresentation(
        header = "Домашнее задание на завтра",
        shortHeader = "ДЗ на завтра",
        items = emptyList(),
        subjectCount = "",
        subjectFigure = "",
        isKnown = true,
    )

    @Test
    fun `every size with a list under it is given the next day's lessons`() {
        val tall = WidgetSizeClass.entries.filter { it.timelineRows > 0 && it != WidgetSizeClass.MEDIUM }
        assertTrue(tall.isNotEmpty())
        tall.forEach { size ->
            assertEquals("$size", tomorrow.lessons, restDayLessons(size, tomorrow, nothingSet))
        }
    }

    @Test
    fun `the two by three takes them only while the homework leaves room`() {
        assertEquals(tomorrow.lessons, restDayLessons(WidgetSizeClass.SMALL_TALL, tomorrow, nothingSet))
        val busy = nothingSet.copy(items = List(3) { HomeworkItem(subject = "Предмет $it", text = "§$it") })
        assertEquals(emptyList<Lesson>(), restDayLessons(WidgetSizeClass.SMALL_TALL, tomorrow, busy))
    }

    @Test
    fun `the one-line and square sizes have no room for a list`() {
        listOf(WidgetSizeClass.TINY, WidgetSizeClass.WIDE, WidgetSizeClass.SMALL, WidgetSizeClass.MEDIUM).forEach {
            assertEquals("$it", emptyList<Lesson>(), restDayLessons(it, tomorrow, nothingSet))
        }
    }
}

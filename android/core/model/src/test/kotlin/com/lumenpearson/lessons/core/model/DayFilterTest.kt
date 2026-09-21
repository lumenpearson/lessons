package com.lumenpearson.lessons.core.model

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrowing a calendar down, and the two decisions that make it usable.
 *
 * Several chosen facets are an OR. An AND would mean the second chip almost
 * always empties the screen — «с ДЗ» and «с событиями» together would ask for
 * days that have both, which most months have none of — and a filter whose
 * second press shows nothing is one nobody presses twice.
 *
 * An empty set is no filter rather than «show nothing», because empty is where
 * this feature rests and the resting state has to be the harmless one.
 */
class DayFilterTest {

    private val monday = LocalDate.parse("2026-09-14")

    private fun lesson(index: Int, cancelled: Boolean = false) = Lesson(
        index = index,
        subject = "Алгебра",
        startsAt = LocalTime.of(8, 30),
        endsAt = LocalTime.of(9, 15),
        isCancelled = cancelled,
    )

    private fun day(
        date: LocalDate = monday,
        lessons: Int = 0,
        homework: Int = 0,
        events: Int = 0,
        kind: DayKind = DayKind.NORMAL,
    ) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        kind = kind,
        lessons = (1..lessons).map { lesson(it) },
        homework = (1..homework).map { HomeworkItem(subject = "Алгебра", text = "§$it") },
        events = (1..events).map {
            SchoolEvent(
                title = "Экскурсия",
                kind = EventKind.TRIP,
                startsAt = LocalTime.of(12, 0),
                endsAt = LocalTime.of(13, 0),
            )
        },
    )

    @Test
    fun `no filter admits everything, including an empty day`() {
        assertTrue(day().matches(emptySet()))
        assertTrue(day(lessons = 5).matches(emptySet()))
    }

    @Test
    fun `a day the cache does not reach never matches a filter`() {
        // «Not loaded» is not «matches»: an empty cell under a chip that
        // promised homework is a worse answer than no cell at all.
        val missing: SchoolDay? = null
        assertFalse(missing.matches(setOf(DayFilter.HAS_HOMEWORK)))
        // …but with no filter on, it is not being asked anything.
        assertTrue(missing.matches(emptySet()))
    }

    @Test
    fun `two facets are an or rather than an and`() {
        val homeworkOnly = day(homework = 2)
        val eventsOnly = day(events = 1)
        val both = setOf(DayFilter.HAS_HOMEWORK, DayFilter.HAS_EVENTS)

        assertTrue("a day with only homework survives", homeworkOnly.matches(both))
        assertTrue("a day with only an event survives", eventsOnly.matches(both))
        assertFalse("a day with neither does not", day().matches(both))
    }

    @Test
    fun `a cancelled lesson is not a lesson to filter on`() {
        // `hasLessons` already says so, and the filter has to agree with the
        // dots the same day draws — otherwise a day shows under «с уроками»
        // with nothing under it.
        assertFalse(day(lessons = 0).matches(setOf(DayFilter.HAS_LESSONS)))
        val allCancelled = SchoolDay(
            date = monday,
            weekday = 1,
            lessons = listOf(lesson(1, cancelled = true)),
        )
        assertFalse(allCancelled.matches(setOf(DayFilter.HAS_LESSONS)))
    }

    @Test
    fun `marked means somebody decided something, not merely empty`() {
        assertFalse("an ordinary empty day is not marked", day().matches(setOf(DayFilter.MARKED)))
        assertTrue(day(kind = DayKind.HOLIDAY).matches(setOf(DayFilter.MARKED)))
        assertTrue(day(kind = DayKind.SELF_STUDY).matches(setOf(DayFilter.MARKED)))
        assertTrue(day(kind = DayKind.SHORTENED).matches(setOf(DayFilter.MARKED)))
    }

    @Test
    fun `a named date on an ordinary day is not a marked day`() {
        // «День учителя» is a badge the server attaches, not a decision this
        // class made. A filter for marked days that returned it would return a
        // date nobody here marked.
        val teachersDay = day(lessons = 6).copy(
            holiday = Holiday("un_teachers_day", "День учителя", stopsLessons = false),
        )
        assertFalse(teachersDay.matches(setOf(DayFilter.MARKED)))
        assertTrue(teachersDay.matches(setOf(DayFilter.HAS_LESSONS)))
    }

    @Test
    fun `dates sort forwards and backwards`() {
        val days = listOf(
            day(date = monday.plusDays(2)),
            day(date = monday),
            day(date = monday.plusDays(1)),
        )

        assertEquals(
            listOf(monday, monday.plusDays(1), monday.plusDays(2)),
            DayOrder.DATE_ASC.sort(days).map { it.date },
        )
        assertEquals(
            listOf(monday.plusDays(2), monday.plusDays(1), monday),
            DayOrder.DATE_DESC.sort(days).map { it.date },
        )
    }

    @Test
    fun `the busiest first, and ties keep a stable order`() {
        val days = listOf(
            day(date = monday.plusDays(1), lessons = 5),
            day(date = monday.plusDays(2), lessons = 7),
            day(date = monday, lessons = 5),
        )

        assertEquals(
            // Seven first; then the two fives, oldest first rather than in
            // whatever order they arrived — a list somebody scrolls twice has
            // to look the same both times.
            listOf(monday.plusDays(2), monday, monday.plusDays(1)),
            DayOrder.BUSIEST_FIRST.sort(days).map { it.date },
        )
    }

    @Test
    fun `sorting never loses or invents a day`() {
        val days = (0..6).map { day(date = monday.plusDays(it.toLong()), lessons = it % 3) }
        for (order in DayOrder.entries) {
            assertEquals(
                "$order changed the set of days rather than their order",
                days.map { it.date }.toSet(),
                order.sort(days).map { it.date }.toSet(),
            )
            assertEquals(days.size, order.sort(days).size)
        }
    }
}

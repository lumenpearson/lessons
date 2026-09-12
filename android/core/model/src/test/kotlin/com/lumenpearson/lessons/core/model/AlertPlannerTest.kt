package com.lumenpearson.lessons.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner decides when a phone buzzes, which makes "one minute early" and
 * "twice" both worse failures than "not at all".
 */
class AlertPlannerTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)
    private val tuesday: LocalDate = monday.plusDays(1)

    private fun lesson(
        index: Int,
        subject: String,
        from: String,
        to: String,
        cancelled: Boolean = false,
    ) = Lesson(
        index = index,
        subject = subject,
        startsAt = LocalTime.parse(from),
        endsAt = LocalTime.parse(to),
        isCancelled = cancelled,
    )

    private val mondayLessons = listOf(
        lesson(1, "Алгебра", "08:30", "09:15"),
        lesson(2, "Физика", "09:25", "10:10"),
    )

    private fun day(
        date: LocalDate,
        lessons: List<Lesson> = mondayLessons,
        homework: List<HomeworkItem> = emptyList(),
    ) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        lessons = lessons,
        homework = homework,
    )

    private fun timetable(vararg days: SchoolDay) = Timetable(
        schoolClass = SchoolClassInfo(id = 1, name = "9А"),
        days = days.toList(),
    )

    private fun at(date: LocalDate, time: String): LocalDateTime =
        LocalDateTime.of(date, LocalTime.parse(time))

    private val lessonsOnly = AlertPreferences(lessonSoon = true, lessonLeadMinutes = 10)

    @Test
    fun `nothing is planned while every switch is off`() {
        val table = timetable(day(monday))

        assertNull(AlertPlanner.next(table, AlertPreferences(), at(monday, "06:00")))
        assertTrue(
            AlertPlanner
                .due(table, AlertPreferences(), at(monday, "08:20"), Duration.ofMinutes(1))
                .isEmpty(),
        )
    }

    @Test
    fun `a lesson alert lands the configured number of minutes early`() {
        val table = timetable(day(monday))

        val next = AlertPlanner.next(table, lessonsOnly, at(monday, "06:00"))

        assertEquals(at(monday, "08:20"), next?.at)
        assertEquals("Алгебра", (next as SchoolAlert.LessonSoon).lesson.subject)
    }

    /** The whole point of the horizon: the alarm chain has to cross a weekend. */
    @Test
    fun `the next alert can be on a later day`() {
        val table = timetable(day(monday), day(tuesday))

        val next = AlertPlanner.next(table, lessonsOnly, at(monday, "12:00"))

        assertEquals(at(tuesday, "08:20"), next?.at)
    }

    @Test
    fun `a cancelled lesson is not announced`() {
        val table = timetable(
            day(monday, lessons = listOf(lesson(1, "Алгебра", "08:30", "09:15", cancelled = true))),
        )

        assertNull(AlertPlanner.next(table, lessonsOnly, at(monday, "06:00")))
    }

    /**
     * The alarm behind this is inexact by design, so an alert is due for a window
     * around the moment rather than at it.
     */
    @Test
    fun `due covers a window either side of the moment`() {
        val table = timetable(day(monday))
        val tolerance = Duration.ofSeconds(90)

        assertEquals(1, AlertPlanner.due(table, lessonsOnly, at(monday, "08:20"), tolerance).size)
        assertEquals(1, AlertPlanner.due(table, lessonsOnly, at(monday, "08:21"), tolerance).size)
        assertTrue(AlertPlanner.due(table, lessonsOnly, at(monday, "08:25"), tolerance).isEmpty())
    }

    /**
     * Re-arming past the window it just fired is the only thing stopping an
     * alert being posted twice, so the strictness of `next` is load-bearing.
     */
    @Test
    fun `next is strictly after the moment it is given`() {
        val table = timetable(day(monday))

        val next = AlertPlanner.next(table, lessonsOnly, at(monday, "08:20"))

        assertEquals(at(monday, "09:15"), next?.at)
    }

    @Test
    fun `the morning summary is skipped on a day with no lessons`() {
        val preferences = AlertPreferences(morningSummary = true, morningAtMinutes = 7 * 60)
        val table = timetable(day(monday, lessons = emptyList()), day(tuesday))

        val next = AlertPlanner.next(table, preferences, at(monday, "00:00"))

        assertEquals(at(tuesday, "07:00"), next?.at)
    }

    /** Homework is filed under the day it is due, so the reminder looks forward. */
    @Test
    fun `the homework reminder names the next school day`() {
        val preferences = AlertPreferences(homeworkReminder = true, homeworkAtMinutes = 20 * 60)
        val table = timetable(
            day(monday),
            day(tuesday, homework = listOf(HomeworkItem(subject = "Алгебра", text = "№ 42"))),
        )

        val next = AlertPlanner.next(table, preferences, at(monday, "12:00"))

        assertEquals(at(monday, "20:00"), next?.at)
        assertEquals(tuesday, (next as SchoolAlert.Homework).day.date)
    }

    @Test
    fun `no homework tomorrow means no reminder tonight`() {
        val preferences = AlertPreferences(homeworkReminder = true, homeworkAtMinutes = 20 * 60)
        val table = timetable(day(monday), day(tuesday))

        assertNull(AlertPlanner.next(table, preferences, at(monday, "12:00")))
    }

    /**
     * The weekend is the case that was wrong, and it is the common one.
     *
     * The server returns a day for every date in the window, weekends included
     * — they simply have no lessons — and "the next school day" skips them. So
     * one set of Monday homework was announced on Friday, on Saturday and again
     * on Sunday, three identical notifications under one id. Walking the week
     * and counting is the shape of test that catches it: asserting a single
     * `next` from one moment cannot, because each of the three is correct on its
     * own.
     */
    @Test
    fun `homework due after the weekend is announced once, on the evening before`() {
        val preferences = AlertPreferences(homeworkReminder = true, homeworkAtMinutes = 20 * 60)
        val friday = monday.minusDays(3)
        val table = timetable(
            day(friday),
            day(friday.plusDays(1), lessons = emptyList()),
            day(friday.plusDays(2), lessons = emptyList()),
            day(monday, homework = listOf(HomeworkItem(subject = "Алгебра", text = "№ 42"))),
        )

        val reminders = generateSequence(at(friday, "00:00")) { moment ->
            AlertPlanner.next(table, preferences, moment)?.at
        }.drop(1).takeWhile { it < at(monday, "00:00") }.toList()

        assertEquals(listOf(at(monday.minusDays(1), "20:00")), reminders)
    }

    /** Two switches on means the earlier of the two wins, not the first one checked. */
    @Test
    fun `the earliest alert wins across kinds`() {
        val preferences = AlertPreferences(
            lessonSoon = true,
            lessonLeadMinutes = 10,
            morningSummary = true,
            morningAtMinutes = 7 * 60,
        )
        val table = timetable(day(monday))

        val next = AlertPlanner.next(table, preferences, at(monday, "06:00"))

        assertTrue(next is SchoolAlert.Morning)
        assertEquals(at(monday, "07:00"), next?.at)
    }

    /** A stored hour of 99 is a corrupt preference, not a reason to throw. */
    @Test
    fun `an impossible stored time is clamped`() {
        val preferences = AlertPreferences(morningSummary = true, morningAtMinutes = 99 * 60)
        val table = timetable(day(monday))

        val next = AlertPlanner.next(table, preferences, at(monday, "00:00"))

        assertEquals(at(monday, "23:59"), next?.at)
    }

    /**
     * Without the exact-alarm permission the alarm is inexact and may arrive
     * minutes after the moment it was armed for. A window centred on the arrival
     * misses everything it was woken up to say — which is the whole feature,
     * silently, on every device that does not grant exactness.
     */
    @Test
    fun `a late delivery still finds what it was armed for`() {
        val table = timetable(day(monday))
        val armedFor = at(monday, "08:20")
        val late = at(monday, "08:24")

        // What the old symmetric window around the arrival would have found.
        assertTrue(AlertPlanner.due(table, lessonsOnly, late, Duration.ofSeconds(90)).isEmpty())

        val due = AlertPlanner.due(
            table,
            lessonsOnly,
            from = armedFor.minusSeconds(90),
            to = late.plusSeconds(90),
        )

        assertEquals(listOf(armedFor), due.map { it.at })
    }

    /**
     * A delivery late enough to have overrun the alert after it announces both,
     * in order, rather than dropping the first and arming past the second.
     */
    @Test
    fun `a window that spans two alerts publishes both, earliest first`() {
        val preferences = AlertPreferences(
            lessonSoon = true,
            lessonLeadMinutes = 10,
            morningSummary = true,
            morningAtMinutes = 8 * 60 + 15,
        )
        val table = timetable(day(monday))

        val due = AlertPlanner.due(
            table,
            preferences,
            from = at(monday, "08:14"),
            to = at(monday, "09:20"),
        )

        assertEquals(listOf(at(monday, "08:15"), at(monday, "08:20"), at(monday, "09:15")), due.map { it.at })
    }

    /** A window the caller has inverted is empty, not a walk of the whole horizon. */
    @Test
    fun `a backwards window finds nothing`() {
        val table = timetable(day(monday))

        assertTrue(
            AlertPlanner
                .due(table, lessonsOnly, from = at(monday, "09:00"), to = at(monday, "08:00"))
                .isEmpty(),
        )
    }
}

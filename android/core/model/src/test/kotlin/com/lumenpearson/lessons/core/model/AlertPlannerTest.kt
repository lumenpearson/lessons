package com.lumenpearson.lessons.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        kind: DayKind = DayKind.NORMAL,
    ) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        kind = kind,
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

    /**
     * Quiet hours are stated as "с 22:00 до 7:00", which is two intervals and
     * not one, and the wrapped case is the one people actually configure.
     */
    @Test
    fun `a quiet window that spans midnight silences both ends of the day`() {
        val preferences = AlertPreferences(
            morningSummary = true,
            morningAtMinutes = 6 * 60,
            quietHours = true,
            quietFromMinutes = 22 * 60,
            quietToMinutes = 7 * 60,
        )
        val table = timetable(day(monday), day(tuesday))

        assertNull(AlertPlanner.next(table, preferences, at(monday, "00:00")))

        // The same summary an hour later, outside the window, is planned as usual.
        val loud = preferences.copy(morningAtMinutes = 7 * 60)
        assertEquals(at(monday, "07:00"), AlertPlanner.next(table, loud, at(monday, "00:00"))?.at)
    }

    /** The end of the window is exclusive, which is where the morning summary lives. */
    @Test
    fun `the moment quiet hours end is not itself quiet`() {
        val preferences = AlertPreferences(
            quietHours = true,
            quietFromMinutes = 22 * 60,
            quietToMinutes = 7 * 60,
        )

        assertTrue(preferences.isQuiet(LocalTime.parse("06:59")))
        assertFalse(preferences.isQuiet(LocalTime.parse("07:00")))
        assertTrue(preferences.isQuiet(LocalTime.parse("22:00")))
    }

    /** A window inside one day is the other half of the same comparison. */
    @Test
    fun `a quiet window inside one day silences only that window`() {
        val preferences = AlertPreferences(
            lessonSoon = true,
            lessonLeadMinutes = 10,
            quietHours = true,
            quietFromMinutes = 8 * 60,
            quietToMinutes = 9 * 60,
        )
        val table = timetable(day(monday))

        // 08:20 is silenced, 09:15 is not.
        assertEquals(at(monday, "09:15"), AlertPlanner.next(table, preferences, at(monday, "00:00"))?.at)
    }

    /**
     * Two ends that are equal is a state a user passes through while setting
     * the second of them, and silencing the whole day for that moment would be
     * a setting that appears to break the app while it is being configured.
     */
    @Test
    fun `a quiet window with equal ends silences nothing`() {
        val preferences = AlertPreferences(
            morningSummary = true,
            morningAtMinutes = 7 * 60,
            quietHours = true,
            quietFromMinutes = 9 * 60,
            quietToMinutes = 9 * 60,
        )
        val table = timetable(day(monday))

        assertEquals(at(monday, "07:00"), AlertPlanner.next(table, preferences, at(monday, "00:00"))?.at)
    }

    /**
     * The lead time decides *when* an alert lands, so it is what quiet hours
     * are applied to — not the lesson.
     *
     * A first lesson at 08:00 with a half-hour lead is a 07:30 notification,
     * and somebody who asked for silence until 08:00 asked for that one not to
     * arrive. Testing the lesson's own time instead would have let it through.
     */
    @Test
    fun `quiet hours judge the moment the alert lands, not the lesson`() {
        val early = listOf(lesson(1, "Алгебра", "08:00", "08:45"))
        val table = timetable(day(monday, lessons = early))
        val preferences = AlertPreferences(
            lessonSoon = true,
            lessonLeadMinutes = 30,
            quietHours = true,
            quietFromMinutes = 22 * 60,
            quietToMinutes = 8 * 60,
        )

        assertNull(AlertPlanner.next(table, preferences, at(monday, "00:00")))

        // Five minutes' lead lands at 07:55 — still inside the window — while
        // ten minutes past the hour is outside it, so the boundary is the
        // alert's own moment and nothing else.
        val short = preferences.copy(lessonLeadMinutes = 5, quietToMinutes = 7 * 60 + 50)
        assertEquals(at(monday, "07:55"), AlertPlanner.next(table, short, at(monday, "00:00"))?.at)
    }

    /**
     * A morning nobody wants the phone to make a sound on.
     *
     * Walking the week rather than asking once: the switch has to keep the
     * other four days, and a single `next` from Sunday cannot tell "Saturday is
     * off" from "the whole thing is off".
     */
    @Test
    fun `a weekday switched off loses its summary and keeps the rest`() {
        val preferences = AlertPreferences(
            morningSummary = true,
            morningAtMinutes = 7 * 60,
            morningWeekdays = AlertPreferences.AllWeekdays - SATURDAY,
        )
        val week = (0L..5L).map { offset -> day(monday.plusDays(offset)) }
        val table = timetable(*week.toTypedArray())

        val summaries = generateSequence(at(monday, "00:00")) { moment ->
            AlertPlanner.next(table, preferences, moment)?.at
        }.drop(1).takeWhile { it < at(monday.plusDays(6), "00:00") }.toList()

        assertEquals(
            (0L..4L).map { offset -> at(monday.plusDays(offset), "07:00") },
            summaries,
        )
    }

    /** Every day off is still a valid answer, and it means nothing is planned. */
    @Test
    fun `a summary with no weekdays left is never planned`() {
        val preferences = AlertPreferences(
            morningSummary = true,
            morningWeekdays = emptySet(),
        )

        assertNull(AlertPlanner.next(timetable(day(monday)), preferences, at(monday, "00:00")))
    }

    /**
     * The case the holiday rule exists for.
     *
     * A school marks a week as каникулы and leaves the lesson rows underneath
     * it, because the timetable is generated from the term's grid. Without the
     * rule the phone rings the morning bell every day of the break from a
     * schedule nobody is following.
     */
    @Test
    fun `a holiday with lessons still cached is left alone`() {
        val preferences = AlertPreferences(
            lessonSoon = true,
            lessonLeadMinutes = 10,
            morningSummary = true,
            morningAtMinutes = 7 * 60,
        )
        val table = timetable(day(monday, kind = DayKind.HOLIDAY), day(tuesday))

        val next = AlertPlanner.next(table, preferences, at(monday, "00:00"))

        assertEquals(at(tuesday, "07:00"), next?.at)
    }

    /** Off, the same day is announced exactly as any other. */
    @Test
    fun `a holiday is announced when the rule is switched off`() {
        val preferences = AlertPreferences(
            morningSummary = true,
            morningAtMinutes = 7 * 60,
            skipHolidays = false,
        )
        val table = timetable(day(monday, kind = DayKind.HOLIDAY))

        assertEquals(at(monday, "07:00"), AlertPlanner.next(table, preferences, at(monday, "00:00"))?.at)
    }

    /**
     * The holiday rule asks about the day the alert is *about*, which is what
     * keeps the one useful notification of the whole break.
     *
     * The homework reminder is posted the evening before and names tomorrow. On
     * the last night of the holidays that evening falls inside the break and
     * tomorrow is a school day with homework on it — so a rule written about
     * the day of posting would delete precisely the reminder somebody who has
     * been off for a week needs most.
     */
    @Test
    fun `the last evening of the holidays still reminds about tomorrow`() {
        val preferences = AlertPreferences(homeworkReminder = true, homeworkAtMinutes = 20 * 60)
        val table = timetable(
            day(monday.minusDays(1), lessons = emptyList(), kind = DayKind.HOLIDAY),
            day(monday, homework = listOf(HomeworkItem(subject = "Алгебра", text = "№ 42"))),
        )

        val next = AlertPlanner.next(table, preferences, at(monday.minusDays(1), "00:00"))

        assertEquals(at(monday.minusDays(1), "20:00"), next?.at)
    }

    /** What the notification is allowed to say travels with the alert. */
    @Test
    fun `the lesson alert carries the detail level it was planned with`() {
        val preferences = lessonsOnly.copy(lessonDetail = LessonAlertDetail.SUBJECT)

        val next = AlertPlanner.next(timetable(day(monday)), preferences, at(monday, "06:00"))

        assertEquals(LessonAlertDetail.SUBJECT, (next as SchoolAlert.LessonSoon).detail)
    }

    private companion object {
        /** ISO-8601: Monday is 1. */
        const val SATURDAY = 6
    }
}

package com.lumenpearson.lessons.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the user has asked to be told about, and when.
 *
 * Times are minutes from midnight rather than [LocalTime] so the whole thing
 * survives a round trip through a preferences file without a formatter, and so
 * the settings screen can offer them as a row of chips.
 *
 * Everything is off by default. A school diary that starts buzzing the moment it
 * is installed is a school diary that gets its notifications turned off at the
 * system level, and that channel is never coming back.
 */
data class AlertPreferences(
    val lessonSoon: Boolean = false,
    val lessonLeadMinutes: Int = DefaultLeadMinutes,
    val morningSummary: Boolean = false,
    val morningAtMinutes: Int = DefaultMorningMinutes,
    val homeworkReminder: Boolean = false,
    val homeworkAtMinutes: Int = DefaultHomeworkMinutes,
    val scheduleChanges: Boolean = false,
) {
    /** True when nothing at all is on, which lets callers skip every alarm. */
    val silent: Boolean
        get() = !lessonSoon && !morningSummary && !homeworkReminder

    companion object {
        /** Long enough to pack a bag, short enough to still be in the lesson before. */
        const val DefaultLeadMinutes: Int = 10

        /** Offered as chips; a slider over minutes would be false precision. */
        val LeadMinuteOptions: List<Int> = listOf(5, 10, 15, 30)

        /** Early enough to change what goes in the bag. */
        const val DefaultMorningMinutes: Int = 7 * 60

        /** After dinner, before the evening is gone. */
        const val DefaultHomeworkMinutes: Int = 20 * 60

        /** The hours a summary or a homework reminder may be set to. */
        val HourOptions: List<Int> = (5..22).toList()
    }
}

/**
 * One thing worth interrupting a pupil for, and the wall-clock moment to do it.
 *
 * The alert carries the data and not the words: it is planned in a pure module
 * with no resources, and the sentence is built where the strings live. That also
 * means the same alert can be rendered differently by the notification and by a
 * test that only cares which lesson it named.
 *
 * @property at school wall time, not device time. The schedule is stored in the
 *   school's zone and a boarding pupil in another one still wants the bell.
 */
sealed interface SchoolAlert {

    val at: LocalDateTime

    /** "Через 10 минут: Алгебра, кабинет 214." */
    data class LessonSoon(
        override val at: LocalDateTime,
        val date: LocalDate,
        val lesson: Lesson,
        val leadMinutes: Int,
    ) : SchoolAlert

    /** "Сегодня 6 уроков, первый — Алгебра в 8:30." */
    data class Morning(
        override val at: LocalDateTime,
        val day: SchoolDay,
    ) : SchoolAlert

    /** "На завтра задано по трём предметам." */
    data class Homework(
        override val at: LocalDateTime,
        val day: SchoolDay,
    ) : SchoolAlert
}

/**
 * Turns a cached timetable plus [AlertPreferences] plus a clock into the alerts
 * that are due now and the next one to wake up for.
 *
 * Pure and synchronous, like [ScheduleEngine] and for the same reason: this is
 * the part that decides whether a phone buzzes in a lesson, and it has to be
 * testable by walking a week in a loop rather than by waiting a week.
 *
 * There is no state here and nothing is remembered between calls. The scheduler
 * fires, asks what is due in a window around now, posts it, then asks for the
 * next moment strictly after that window and arms for it — so an alert cannot be
 * posted twice without the clock going backwards.
 */
object AlertPlanner {

    /**
     * How far ahead [next] will look for something to arm.
     *
     * A week and a day: enough to cross a holiday and still land on the Monday
     * morning summary, short enough that a stale cache cannot arm an alarm for a
     * lesson that will have been rescheduled twice by the time it rings.
     */
    private const val HorizonDays = 8L

    /**
     * Everything due within [tolerance] of [now].
     *
     * A window rather than an instant because the alarm that triggers this is
     * inexact by design — the widget's scheduler already degrades to a
     * one-minute window when the exact-alarm permission is absent — so "fired at
     * 08:20:37 for an 08:20:00 alert" has to count.
     */
    fun due(
        timetable: Timetable,
        preferences: AlertPreferences,
        now: LocalDateTime,
        tolerance: Duration,
    ): List<SchoolAlert> {
        if (preferences.silent) return emptyList()
        val from = now.minus(tolerance)
        val to = now.plus(tolerance)
        return candidates(timetable, preferences, now.toLocalDate().minusDays(1), days = 3)
            .filter { it.at >= from && it.at <= to }
            .sortedBy { it.at }
    }

    /** The earliest alert strictly after [after], or null if there is none to arm. */
    fun next(
        timetable: Timetable,
        preferences: AlertPreferences,
        after: LocalDateTime,
    ): SchoolAlert? {
        if (preferences.silent) return null
        return candidates(timetable, preferences, after.toLocalDate(), days = HorizonDays.toInt())
            .filter { it.at > after }
            .minByOrNull { it.at }
    }

    /**
     * Every alert the preferences would produce for [days] days from [start].
     *
     * Built eagerly and filtered by the callers rather than generated lazily:
     * the widest window is eight days of at most eight lessons, which is under a
     * hundred objects, and a sequence would buy nothing but a harder read.
     */
    private fun candidates(
        timetable: Timetable,
        preferences: AlertPreferences,
        start: LocalDate,
        days: Int,
    ): List<SchoolAlert> = buildList {
        for (offset in 0 until days) {
            val date = start.plusDays(offset.toLong())
            val day = timetable.day(date) ?: continue

            if (preferences.lessonSoon) {
                day.activeLessons.forEach { lesson ->
                    // Crossing midnight is not a real school day, but a 30-minute
                    // lead on a 00:10 lesson would otherwise plan an alert for
                    // the previous day and never be found by a window around it.
                    val at = date.atTime(lesson.startsAt).minusMinutes(preferences.lessonLeadMinutes.toLong())
                    if (at.toLocalDate() == date) {
                        add(
                            SchoolAlert.LessonSoon(
                                at = at,
                                date = date,
                                lesson = lesson,
                                leadMinutes = preferences.lessonLeadMinutes,
                            ),
                        )
                    }
                }
            }

            // Nothing to summarise on a day off, and a pupil who is told "сегодня
            // нет уроков" at seven in the morning on every holiday learns to
            // dismiss the app rather than to read it.
            if (preferences.morningSummary && day.hasLessons) {
                add(SchoolAlert.Morning(at = date.atTime(minutesToTime(preferences.morningAtMinutes)), day = day))
            }

            // The evening before, about the day it is set for. Homework is filed
            // under the day it is due, so the reminder has to look forward.
            if (preferences.homeworkReminder) {
                val target = timetable.schoolDayAfter(date)
                if (target != null && target.homework.isNotEmpty()) {
                    add(
                        SchoolAlert.Homework(
                            at = date.atTime(minutesToTime(preferences.homeworkAtMinutes)),
                            day = target,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Clamped rather than thrown on: a corrupt preference must not kill a
     * receiver. The whole value is clamped before it is split, so a stored 99:00
     * becomes the last minute of the day rather than a plausible-looking 23:00.
     */
    private fun minutesToTime(minutes: Int): LocalTime =
        LocalTime.ofSecondOfDay(minutes.coerceIn(0, MinutesPerDay - 1).toLong() * 60L)

    private const val MinutesPerDay = 24 * 60
}

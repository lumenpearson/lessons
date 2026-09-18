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
 * the settings screen can hand one straight to a clock dial and take one back.
 *
 * Everything that posts is off by default. A school diary that starts buzzing
 * the moment it is installed is a school diary that gets its notifications
 * turned off at the system level, and that channel is never coming back.
 *
 * The rules that only ever *suppress* — [skipHolidays] — start on for the
 * mirror image of the same reason: the first notification anybody gets from
 * this app should not be a bell for a lesson that is not happening.
 */
data class AlertPreferences(
    val lessonSoon: Boolean = false,
    val lessonLeadMinutes: Int = DefaultLeadMinutes,
    /** How much of the lesson the reminder spells out; see [LessonAlertDetail]. */
    val lessonDetail: LessonAlertDetail = LessonAlertDetail.FULL,
    val morningSummary: Boolean = false,
    val morningAtMinutes: Int = DefaultMorningMinutes,
    /**
     * Weekdays the morning summary may be posted on, ISO-8601 numbered
     * (1 = Monday … 7 = Sunday).
     *
     * All seven by default, which changes nothing for anybody: a day with no
     * lessons is already skipped. It earns its keep in the schools that do
     * teach on Saturday and in the families where Saturday is the one morning
     * nobody wants a phone to make a sound, and those two cannot both be
     * served by a rule the app decides on its own.
     */
    val morningWeekdays: Set<Int> = AllWeekdays,
    val homeworkReminder: Boolean = false,
    val homeworkAtMinutes: Int = DefaultHomeworkMinutes,
    val scheduleChanges: Boolean = false,
    /**
     * Whether the hours in [quietFromMinutes]..[quietToMinutes] are silent.
     *
     * Off by default. Every alert here is already tied to a school day, so a
     * quiet window that nobody asked for would only ever delete something.
     */
    val quietHours: Boolean = false,
    val quietFromMinutes: Int = DefaultQuietFromMinutes,
    val quietToMinutes: Int = DefaultQuietToMinutes,
    /**
     * Whether a date the school has marked as holidays is left alone.
     *
     * On by default, and it is not the no-op it looks like: a school that puts
     * каникулы on the calendar rarely deletes the lesson rows underneath, so
     * without this a week of holidays rings the morning bell every day from a
     * timetable nobody is following.
     */
    val skipHolidays: Boolean = true,
) {
    /** True when nothing at all is on, which lets callers skip every alarm. */
    val silent: Boolean
        get() = !lessonSoon && !morningSummary && !homeworkReminder

    /**
     * Whether [at] falls inside the quiet window.
     *
     * The window is half-open — quiet from [quietFromMinutes] inclusive to
     * [quietToMinutes] exclusive — so "с 22:00 до 7:00" leaves 07:00 itself
     * loud, which is where people put the morning summary.
     *
     * A window that ends where it starts is treated as no window at all rather
     * than as the whole day: the two ends are picked one at a time, so a user
     * passes through "равны" on the way to any other pair, and a momentary
     * "everything is silenced" is a worse answer than "nothing is".
     */
    fun isQuiet(at: LocalTime): Boolean {
        if (!quietHours) return false
        val from = quietFromMinutes.coerceIn(0, MinutesPerDay - 1)
        val to = quietToMinutes.coerceIn(0, MinutesPerDay - 1)
        if (from == to) return false
        val minute = at.hour * 60 + at.minute
        // Crossing midnight is the normal case for a quiet window, so it is the
        // shape the comparison is written for: outside it the window is one
        // interval, inside it is the two ends of the day.
        return if (from < to) minute in from until to else minute >= from || minute < to
    }

    companion object {
        /** Long enough to pack a bag, short enough to still be in the lesson before. */
        const val DefaultLeadMinutes: Int = 10

        /** Offered as chips; a slider over minutes would be false precision. */
        val LeadMinuteOptions: List<Int> = listOf(5, 10, 15, 30)

        /** What a stored lead is clamped to; see where it is read. */
        const val MaxLeadMinutes: Int = 60

        /** Early enough to change what goes in the bag. */
        const val DefaultMorningMinutes: Int = 7 * 60

        /** After dinner, before the evening is gone. */
        const val DefaultHomeworkMinutes: Int = 20 * 60

        /** Bedtime on a school night. */
        const val DefaultQuietFromMinutes: Int = 22 * 60

        /** Before the hour anybody would ask to be woken by a summary. */
        const val DefaultQuietToMinutes: Int = 7 * 60

        /** ISO-8601 weekday numbers, Monday first; the order the chips are drawn in. */
        val Weekdays: List<Int> = (1..7).toList()

        /** @see morningWeekdays */
        val AllWeekdays: Set<Int> = Weekdays.toSet()

        internal const val MinutesPerDay: Int = 24 * 60
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
        /**
         * How much of [lesson] the sentence should name.
         *
         * Carried on the alert rather than read from the preferences where the
         * words are built, for the same reason [leadMinutes] is: the planner is
         * the only thing that holds the preferences, and an alert that had to
         * be rendered against a *later* reading of them could say "через 10
         * минут" from one setting and name the room from another.
         */
        val detail: LessonAlertDetail = LessonAlertDetail.FULL,
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
    ): List<SchoolAlert> = due(timetable, preferences, from = now.minus(tolerance), to = now.plus(tolerance))

    /**
     * Everything due in the closed window [from]..[to].
     *
     * The window is stated as two moments rather than as a radius because a
     * caller woken by an inexact alarm knows something the radius cannot
     * express: which moment it was *armed* for. `setAndAllowWhileIdle` may
     * deliver minutes late, and a symmetric window around the late arrival
     * contains neither the alert that was armed nor the ones it overran — so
     * the scheduler asks from the armed moment to now instead, and nothing is
     * dropped for having been delivered late.
     */
    fun due(
        timetable: Timetable,
        preferences: AlertPreferences,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<SchoolAlert> {
        if (preferences.silent || from > to) return emptyList()
        // One day either side of the window, because a lead time can pull an
        // alert back across midnight and the window itself can span two dates.
        val start = from.toLocalDate().minusDays(1)
        val days = Duration.between(start.atStartOfDay(), to).toDays().toInt() + 2
        return candidates(timetable, preferences, start, days = days)
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

            // Every rule below asks about the day the alert is *about*, never
            // the day it would be posted on. That is what keeps the homework
            // reminder — planned the evening before, about tomorrow — working on
            // the last night of the holidays, which is the one night of them it
            // is worth anything.
            val skipped = preferences.skipHolidays && day.kind == DayKind.HOLIDAY

            if (preferences.lessonSoon && !skipped) {
                // Clamped here the way `minutesToTime` clamps the two stored
                // clock times, and for the same reason: this is the one stored
                // minute value nothing checked, and it goes straight into a
                // `getQuantityString`, so a preference file that came back
                // wrong printed «Через -5 минуты» over a lesson it had already
                // started. The ceiling is the chips' largest, doubled.
                val lead = preferences.lessonLeadMinutes.coerceIn(0, MaxLeadMinutes)
                day.activeLessons.forEach { lesson ->
                    // Crossing midnight is not a real school day, but a 30-minute
                    // lead on a 00:10 lesson would otherwise plan an alert for
                    // the previous day and never be found by a window around it.
                    val at = date.atTime(lesson.startsAt).minusMinutes(lead.toLong())
                    if (at.toLocalDate() == date && !preferences.isQuiet(at.toLocalTime())) {
                        add(
                            SchoolAlert.LessonSoon(
                                at = at,
                                date = date,
                                lesson = lesson,
                                leadMinutes = lead,
                                detail = preferences.lessonDetail,
                            ),
                        )
                    }
                }
            }

            // Nothing to summarise on a day off, and a pupil who is told "сегодня
            // нет уроков" at seven in the morning on every holiday learns to
            // dismiss the app rather than to read it.
            if (preferences.morningSummary && day.hasLessons && !skipped &&
                date.dayOfWeek.value in preferences.morningWeekdays
            ) {
                val at = date.atTime(minutesToTime(preferences.morningAtMinutes))
                if (!preferences.isQuiet(at.toLocalTime())) {
                    add(SchoolAlert.Morning(at = at, day = day))
                }
            }

            // The evening before, about the day it is set for. Homework is filed
            // under the day it is due, so the reminder has to look forward.
            //
            // "The evening before" is the guard, and it has to be stated: every
            // cached date walks through here, and `schoolDayAfter` skips days
            // without lessons, so Monday's homework was the answer on Friday,
            // Saturday and Sunday alike — three identical notifications under
            // one id for one set of homework, and one every night of a holiday.
            if (preferences.homeworkReminder) {
                val target = timetable.schoolDayAfter(date)
                    ?.takeIf { it.date == date.plusDays(1) }
                val about = target?.takeUnless { preferences.skipHolidays && it.kind == DayKind.HOLIDAY }
                if (about != null && about.homework.isNotEmpty()) {
                    val at = date.atTime(minutesToTime(preferences.homeworkAtMinutes))
                    if (!preferences.isQuiet(at.toLocalTime())) {
                        add(SchoolAlert.Homework(at = at, day = about))
                    }
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

    private const val MinutesPerDay = AlertPreferences.MinutesPerDay

    private const val MaxLeadMinutes = AlertPreferences.MaxLeadMinutes
}

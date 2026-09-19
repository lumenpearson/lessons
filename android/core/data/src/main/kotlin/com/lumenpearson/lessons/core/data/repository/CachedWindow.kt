package com.lumenpearson.lessons.core.data.repository

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * How much of the cached school year a reader that is not drawing the calendar
 * actually needs.
 *
 * One window rather than one per caller, because the two that exist want
 * almost the same thing and a second number would be a second thing to get
 * wrong: the widget draws this week's load dots and the next few days, and the
 * alarm chain plans a week and a day ahead of a moment that may be half an
 * hour in the past. The union of those is a fortnight; the cache is a school
 * year, some two hundred days of lessons, events and homework, and every one
 * of them was being read to answer either question.
 *
 * Anything that needs a date outside this has to read the whole cache. That is
 * not a detail to be tidied later — see `TimetableRepository.snapshotAroundToday`
 * for what a bounded reading of a timetable is allowed to be asked.
 */
internal object CachedWindow {

    /**
     * How far ahead the bound reaches, in days.
     *
     * `AlertPlanner.next` looks a week and a day ahead from the moment it is
     * given, and the homework rule inside it asks about the day after the last
     * one it looks at. Eight is that number. It is a constant in another
     * module and cannot be imported — it is private there — so this one is
     * held by `CachedWindowTest` instead, which walks the chain over a bounded
     * reading and fails if what the planner reaches for is not in it.
     */
    const val DaysAhead: Long = 8L

    /**
     * The bound, around [today] as the *school* reckons it.
     *
     * The backward reach is the part that is easy to get wrong. A window
     * spelled `today .. today + n` is right six days a week and wrong on
     * Sunday, when the Monday of the current week is six days behind: the
     * widget's week strip would lose six of its seven day-load dots on the one
     * day of the week somebody is most likely to be looking at the week ahead.
     *
     * Yesterday is in it whatever the weekday, and that is not the same
     * sentence as the Monday one. An alarm may be delivered up to half an hour
     * late, and it publishes the window it was armed for rather than a window
     * around its arrival — so one armed for Sunday evening and delivered after
     * midnight on Monday asks about Sunday, which `previousOrSame(MONDAY)`
     * alone would have left out.
     */
    fun around(today: LocalDate): ClosedRange<LocalDate> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return minOf(monday, today.minusDays(1))..today.plusDays(DaysAhead)
    }
}

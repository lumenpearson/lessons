package com.lumenpearson.lessons.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit

/**
 * When the school year runs, which is the horizon everything else is drawn over.
 *
 * The mirror of `school_year_bounds` in the server's `app/schedule.py`, and it
 * has to stay one: the client asks for exactly this range and the server caps
 * exactly this range, so a disagreement is a window that silently comes back
 * short. `SchoolYearTest` pins the same cases the server's own test pins.
 *
 * The calendar used to hold a rolling month, so every date past it read
 * «Нет данных» — not because there were no lessons but because nothing had been
 * fetched, which is indistinguishable on screen and reads as data that stops a
 * month after the class was made.
 */
object SchoolYear {

    /**
     * First teaching day of the year opening in [openingYear].
     *
     * The first of September unless it lands on a weekend, in which case
     * teaching starts on the Monday after.
     */
    fun start(openingYear: Int): LocalDate {
        val first = LocalDate.of(openingYear, Month.SEPTEMBER, 1)
        return when (first.dayOfWeek) {
            DayOfWeek.SATURDAY -> first.plusDays(2)
            DayOfWeek.SUNDAY -> first.plusDays(1)
            else -> first
        }
    }

    /**
     * Last day of the year opening in [openingYear]: the end of May.
     *
     * June is left out deliberately — exams and then holidays. A horizon that
     * ran through it would repeat the weekly template into weeks where nobody
     * has lessons.
     */
    fun end(openingYear: Int): LocalDate = LocalDate.of(openingYear + 1, Month.MAY, 31)

    /**
     * The year [on] belongs to.
     *
     * A summer date belongs to the year about to open rather than the one that
     * has just ended: in July «какое у меня расписание» is a question about
     * September.
     */
    fun boundsAt(on: LocalDate): ClosedRange<LocalDate> {
        val opening = if (on.monthValue >= Month.SEPTEMBER.value) on.year else on.year - 1
        val end = end(opening)
        return if (on > end && on.monthValue < Month.SEPTEMBER.value) {
            start(on.year)..end(on.year)
        } else {
            start(opening)..end
        }
    }

    /** How many days that year spans, both ends included. */
    fun daysAt(on: LocalDate): Int {
        val bounds = boundsAt(on)
        return ChronoUnit.DAYS.between(bounds.start, bounds.endInclusive).toInt() + 1
    }
}

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

    /**
     * The year [on] belongs to, named by the calendar year it opens in.
     *
     * One number, and that is the point: once the cache holds several years it
     * needs something to file them under, and a pair of dates is two values
     * that can disagree. «2026» means the year that opens in September 2026 and
     * ends in May 2027 — which is also how a school says it — so an identifier,
     * an `ETag` signature and a row's primary key can all be that integer and
     * mean the same thing.
     *
     * Derived from [boundsAt] rather than beside it, because the summer rule is
     * subtle enough to be worth having in exactly one place: a July date
     * belongs to the year *about to* open.
     */
    fun openingYearOf(on: LocalDate): Int = boundsAt(on).start.let { start ->
        // The start is 1 September or the Monday after, so its calendar year is
        // the opening year by construction. Reading it off the bound rather
        // than recomputing the month test is what keeps the two from drifting.
        start.year
    }

    /** The bounds of the year opening in [openingYear], both ends included. */
    fun boundsOf(openingYear: Int): ClosedRange<LocalDate> =
        start(openingYear)..end(openingYear)

    /** How many days the year opening in [openingYear] spans, both ends included. */
    fun daysOf(openingYear: Int): Int {
        val bounds = boundsOf(openingYear)
        return ChronoUnit.DAYS.between(bounds.start, bounds.endInclusive).toInt() + 1
    }
}

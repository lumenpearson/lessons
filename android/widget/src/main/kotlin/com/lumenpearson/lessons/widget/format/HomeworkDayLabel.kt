package com.lumenpearson.lessons.widget.format

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How the homework header should name the day the homework is *for*.
 *
 * This is split from the string lookup on purpose. The naming rule is the fiddly
 * part — it has to read naturally in Russian, where "на среду" and "на 15
 * сентября" are different grammatical cases — while the strings themselves are
 * per-locale resources. Keeping the rule as a pure value means it can be unit
 * tested with plain JUnit and no Android at all, and means `values-en/` can
 * spell the same five cases differently without touching this logic.
 *
 * Resolve one into text with [WidgetStrings.homeworkHeader].
 */
sealed interface HomeworkDayLabel {

    /**
     * The homework day is today. Defensive: [DayState.AfterSchool] normally
     * points at a future day, but a server that publishes tomorrow's homework
     * late can leave the pointer on the current date, and "на сегодня" is a much
     * better answer than an off-by-one "на завтра".
     */
    data object Today : HomeworkDayLabel

    /** The common case during the week: "на завтра". */
    data object Tomorrow : HomeworkDayLabel

    /**
     * "на послезавтра". Worth its own case because Russian has a single word for
     * it, and because it is what Friday afternoon shows when Saturday is a day
     * off but Sunday is not — rare, but it happens with Saturday schools.
     */
    data object DayAfterTomorrow : HomeworkDayLabel

    /**
     * A weekday name, three to six days out: "на понедельник". This is the
     * Friday-afternoon case that the whole feature exists for.
     */
    data class Weekday(val dayOfWeek: DayOfWeek) : HomeworkDayLabel

    /** Anything further away, e.g. after the holidays: "на 15 сентября". */
    data class ExplicitDate(val date: LocalDate) : HomeworkDayLabel

    companion object {

        /**
         * Furthest offset that still gets a weekday name.
         *
         * Six, not seven: at exactly seven days the weekday name repeats today's,
         * so "на понедельник" read on a Monday is ambiguous between "tomorrow-ish"
         * and "a week away". Those fall through to an explicit date, which is
         * unambiguous and, at that distance, what a person would say anyway.
         */
        const val MAX_WEEKDAY_OFFSET_DAYS: Long = 6

        /**
         * Picks the label for homework due on [target], read on [today].
         *
         * A [target] in the past collapses to [Today]: the widget must never
         * claim homework is due "на вчера", and a stale cache is the likeliest
         * cause.
         */
        fun of(target: LocalDate, today: LocalDate): HomeworkDayLabel =
            when (val delta = ChronoUnit.DAYS.between(today, target)) {
                in Long.MIN_VALUE..0L -> Today
                1L -> Tomorrow
                2L -> DayAfterTomorrow
                in 3L..MAX_WEEKDAY_OFFSET_DAYS -> Weekday(target.dayOfWeek)
                else -> {
                    check(delta > MAX_WEEKDAY_OFFSET_DAYS) { "unreachable delta $delta" }
                    ExplicitDate(target)
                }
            }
    }
}

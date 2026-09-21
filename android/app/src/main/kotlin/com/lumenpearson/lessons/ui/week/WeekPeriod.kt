package com.lumenpearson.lessons.ui.week

import com.lumenpearson.lessons.core.model.WeekStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Which dates the calendar covers, which of them it draws, and which one the
 * detail panel lands on.
 *
 * Pure date arithmetic, kept apart from the state holder because it is the part
 * of this screen worth checking on the JVM: two of the calendar's settings —
 * where a week begins and whether weekends are drawn — exist nowhere else, and
 * an off-by-one here is an off-by-one in every view at once.
 */

/** Days per row; a week is seven dates however it is aligned. */
internal const val DaysInWeek = 7

/**
 * First and last date the view covers for an anchor inside it.
 *
 * Only the week honours [weekStart]. A month grid is seven columns wide by
 * construction and its rows have to line up under one header, so it stays
 * Monday-aligned even for somebody whose week starts wherever they are.
 */
internal fun ScheduleView.periodOf(
    anchor: LocalDate,
    weekStart: WeekStart,
): Pair<LocalDate, LocalDate> = when (this) {
    ScheduleView.WEEK -> {
        val start = when (weekStart) {
            WeekStart.MONDAY -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            WeekStart.TODAY -> anchor
        }
        start to start.plusDays((DaysInWeek - 1).toLong())
    }

    // The whole grid, not the whole month: a month that starts on a Thursday
    // needs the three days before it to fill its first row, and a cell that is
    // blank is a cell nobody can tap.
    ScheduleView.MONTH -> {
        val first = anchor.withDayOfMonth(1)
        val last = anchor.with(TemporalAdjusters.lastDayOfMonth())
        first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to
            last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    }

    ScheduleView.DAY -> anchor to anchor

    // The month itself, without the neighbouring days a grid needs to fill its
    // corners: a list has no corners, and «30 ноября» at the top of December
    // would be a row nobody was asking for.
    ScheduleView.AGENDA ->
        anchor.withDayOfMonth(1) to anchor.with(TemporalAdjusters.lastDayOfMonth())
}

/**
 * Every date from [start] to [end] inclusive that this view actually draws.
 *
 * Weekends are dropped from the strip only. The month keeps them whatever the
 * setting says — five-column rows would no longer be a month grid — and the day
 * view is one date the user asked for by name.
 */
internal fun ScheduleView.datesOf(
    start: LocalDate,
    end: LocalDate,
    showWeekends: Boolean,
): List<LocalDate> {
    val span = (end.toEpochDay() - start.toEpochDay()).toInt()
    val dates = (0..span).map { offset -> start.plusDays(offset.toLong()) }
    if (this != ScheduleView.WEEK || showWeekends) return dates
    return dates.filterNot { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
}

/**
 * The date the detail panel renders.
 *
 * Compared against the drawn dates rather than against the period's ends: with
 * weekends hidden, Saturday is inside the week and is not on screen, and a
 * selection there left the panel explaining that it had no data for a day the
 * cache knew perfectly well.
 */
internal fun clampSelection(dates: List<LocalDate>, selected: LocalDate): LocalDate =
    if (selected in dates) selected else dates.firstOrNull() ?: selected

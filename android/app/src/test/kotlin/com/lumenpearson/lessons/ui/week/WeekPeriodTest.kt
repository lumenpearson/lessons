package com.lumenpearson.lessons.ui.week

import com.lumenpearson.lessons.core.model.WeekStart
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The date arithmetic behind the calendar's two structural settings.
 *
 * Nothing on that screen says which dates it decided to draw — the strip just
 * looks plausible whatever comes out of here — so the week's first day, the
 * hidden weekends and the selection that has to survive both are checked
 * against dates rather than against pixels.
 */
class WeekPeriodTest {

    /** A Thursday, so a Monday-aligned week and a rolling one cannot coincide. */
    private val thursday: LocalDate = LocalDate.of(2026, 9, 10)

    @Test
    fun `a Monday-aligned week starts on the Monday before the anchor`() {
        val (start, end) = ScheduleView.WEEK.periodOf(thursday, WeekStart.MONDAY)

        assertEquals(LocalDate.of(2026, 9, 7), start)
        assertEquals(LocalDate.of(2026, 9, 13), end)
    }

    @Test
    fun `a rolling week starts on the anchor itself`() {
        val (start, end) = ScheduleView.WEEK.periodOf(thursday, WeekStart.TODAY)

        assertEquals(thursday, start)
        assertEquals(LocalDate.of(2026, 9, 16), end)
    }

    /** Seven dates either way; only where they begin differs. */
    @Test
    fun `a week is seven days whatever it starts on`() {
        WeekStart.entries.forEach { weekStart ->
            val (start, end) = ScheduleView.WEEK.periodOf(thursday, weekStart)
            assertEquals(
                "$weekStart",
                DaysInWeek,
                ScheduleView.WEEK.datesOf(start, end, showWeekends = true).size,
            )
        }
    }

    /**
     * The month grid is seven columns wide by construction, so it stays
     * Monday-aligned for somebody whose week starts today.
     */
    @Test
    fun `the month grid ignores the week start`() {
        val monday = ScheduleView.MONTH.periodOf(thursday, WeekStart.MONDAY)
        val rolling = ScheduleView.MONTH.periodOf(thursday, WeekStart.TODAY)

        assertEquals(monday, rolling)
        assertEquals(DayOfWeek.MONDAY, monday.first.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, monday.second.dayOfWeek)
    }

    /** The grid covers the whole month, plus the corners that fill its rows. */
    @Test
    fun `the month grid spans whole rows around the month`() {
        val (start, end) = ScheduleView.MONTH.periodOf(thursday, WeekStart.MONDAY)
        val dates = ScheduleView.MONTH.datesOf(start, end, showWeekends = true)

        assertEquals(0, dates.size % DaysInWeek)
        assertTrue(LocalDate.of(2026, 9, 1) in dates)
        assertTrue(LocalDate.of(2026, 9, 30) in dates)
    }

    @Test
    fun `the day view covers one date`() {
        val (start, end) = ScheduleView.DAY.periodOf(thursday, WeekStart.MONDAY)

        assertEquals(thursday to thursday, start to end)
        assertEquals(listOf(thursday), ScheduleView.DAY.datesOf(start, end, showWeekends = false))
    }

    @Test
    fun `hidden weekends leave five weekdays in the strip`() {
        val (start, end) = ScheduleView.WEEK.periodOf(thursday, WeekStart.MONDAY)
        val dates = ScheduleView.WEEK.datesOf(start, end, showWeekends = false)

        assertEquals(5, dates.size)
        assertEquals(DayOfWeek.MONDAY, dates.first().dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY, dates.last().dayOfWeek)
    }

    /** A rolling week is still seven consecutive days, so it still loses two. */
    @Test
    fun `hidden weekends thin a rolling week too`() {
        val (start, end) = ScheduleView.WEEK.periodOf(thursday, WeekStart.TODAY)
        val dates = ScheduleView.WEEK.datesOf(start, end, showWeekends = false)

        assertEquals(5, dates.size)
        assertTrue(dates.none { it.dayOfWeek == DayOfWeek.SATURDAY })
        assertTrue(dates.none { it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    /**
     * The month keeps its weekends whatever the setting says: five-column rows
     * would no longer line up with any calendar the user has seen.
     */
    @Test
    fun `the month keeps its weekends`() {
        val (start, end) = ScheduleView.MONTH.periodOf(thursday, WeekStart.MONDAY)

        assertEquals(
            ScheduleView.MONTH.datesOf(start, end, showWeekends = true),
            ScheduleView.MONTH.datesOf(start, end, showWeekends = false),
        )
    }

    @Test
    fun `a drawn selection is kept`() {
        val dates = listOf(thursday.minusDays(1), thursday, thursday.plusDays(1))

        assertEquals(thursday, clampSelection(dates, thursday))
    }

    /** A Saturday selected before the weekends were hidden lands on the Monday. */
    @Test
    fun `a selection that is not drawn falls back to the first date`() {
        val (start, end) = ScheduleView.WEEK.periodOf(thursday, WeekStart.MONDAY)
        val dates = ScheduleView.WEEK.datesOf(start, end, showWeekends = false)

        assertEquals(dates.first(), clampSelection(dates, LocalDate.of(2026, 9, 12)))
    }

    /** Nothing to fall back to: the selection stands rather than becoming null. */
    @Test
    fun `an empty calendar keeps the selection`() {
        assertEquals(thursday, clampSelection(emptyList(), thursday))
    }
}

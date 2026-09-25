package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which days the import asks for, and the date rules the screen shares with it. */
class DiaryImportPlanTest {

    @Test
    fun `weeks are two whole Monday-aligned weeks`() {
        var day = LocalDate.parse("2026-09-01")
        while (day.isBefore(LocalDate.parse("2027-09-01"))) {
            val plan = DiaryImportPlan.of(day)
            assertEquals(DayOfWeek.MONDAY, plan.weeksFrom.dayOfWeek)
            assertEquals(DayOfWeek.SUNDAY, plan.weeksTo.dayOfWeek)
            assertEquals(13, ChronoUnit.DAYS.between(plan.weeksFrom, plan.weeksTo))
            assertTrue(day in plan.weeksFrom..plan.weeksFrom.plusDays(6))
            assertEquals(2, plan.mondays.size)
            assertTrue(DiaryWindows.isWholeWeeks(plan.weeksFrom, plan.weeksTo))
            day = day.plusDays(1)
        }
    }

    @Test
    fun `no span exceeds 62 days, with or without a term`() {
        val terms = listOf(
            null,
            period("2026-09-01", "2026-10-26"),
            period("2026-09-01", "2026-12-28"),
            period("2027-01-09", "2027-05-31"),
            period(null, null),
        )
        var day = LocalDate.parse("2026-09-01")
        while (day.isBefore(LocalDate.parse("2027-09-01"))) {
            val plan = DiaryImportPlan.of(day)
            assertTrue(ChronoUnit.DAYS.between(plan.weeksFrom, plan.weeksTo) <= DiaryRepository.MAX_RANGE_DAYS)
            for (term in terms) {
                val window = plan.marksWindow(term)
                val span = ChronoUnit.DAYS.between(window.from, window.to)
                assertTrue("$day $term: $span", span in 0..DiaryRepository.MAX_RANGE_DAYS)
                assertFalse("$day $term: ends after today", window.to.isAfter(day))
            }
            day = day.plusDays(1)
        }
    }

    @Test
    fun `the marks window follows the term the way the marks tab cuts it`() {
        val today = LocalDate.parse("2026-12-10")
        // A term that fits: the whole of it so far.
        assertEquals(
            DiaryDateRange(LocalDate.parse("2026-11-05"), today),
            DiaryImportPlan.of(today).marksWindow(period("2026-11-05", "2026-12-28")),
        )
        // A term longer than the limit: its latest 62 days.
        assertEquals(
            DiaryDateRange(today.minusDays(62), today),
            DiaryImportPlan.of(today).marksWindow(period("2026-09-01", "2026-12-28")),
        )
        // A term already over ends at its own end.
        assertEquals(
            DiaryDateRange(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-26")),
            DiaryImportPlan.of(today).marksWindow(period("2026-09-01", "2026-10-26")),
        )
    }

    @Test
    fun `the weights sum to one`() {
        assertEquals(1f, DiaryImportPhase.entries.sumOf { it.weight.toDouble() }.toFloat(), 1e-6f)
    }

    @Test
    fun `today is the diary's date, not the phone's`() {
        val clock = java.time.Clock.fixed(Instant.parse("2026-09-27T21:30:00Z"), ZoneId.of("UTC"))
        assertEquals(LocalDate.parse("2026-09-28"), DiaryWindows.today(ZoneId.of("Asia/Tomsk"), clock))
        assertEquals(LocalDate.parse("2026-09-28"), DiaryWindows.today(ZoneId.of("Europe/Moscow"), clock))
        assertEquals(LocalDate.parse("2026-09-27"), DiaryWindows.today(ZoneId.of("Europe/Kaliningrad"), clock))
    }

    @Test
    fun `fresh means younger than half an hour, and never a read from the future`() {
        val now = Instant.parse("2026-09-25T10:00:00Z")
        assertTrue(diaryIsFresh(now.minusSeconds(29 * 60), now))
        assertFalse(diaryIsFresh(now.minusSeconds(30 * 60), now))
        assertFalse(diaryIsFresh(null, now))
        // A clock set back must not freeze the refresh for however far it went.
        assertFalse(diaryIsFresh(now.plusSeconds(60), now))
    }

    private fun period(from: String?, to: String?) = DiaryPeriod(
        id = 1,
        name = "term",
        startsOn = from?.let(LocalDate::parse),
        endsOn = to?.let(LocalDate::parse),
        isCurrent = true,
    )
}

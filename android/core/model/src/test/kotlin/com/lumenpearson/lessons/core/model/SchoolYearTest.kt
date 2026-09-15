package com.lumenpearson.lessons.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same cases the server's `tests/test_school_year.py` pins.
 *
 * Two implementations of one rule disagree within a month unless something
 * says so; here the something is that both test files carry the same dates.
 */
class SchoolYearTest {

    @Test
    fun `the year opens on a weekday`() {
        // 2025 Monday, 2026 Tuesday, 2027 Wednesday — the 1st itself.
        assertEquals(LocalDate.of(2025, 9, 1), SchoolYear.start(2025))
        assertEquals(LocalDate.of(2026, 9, 1), SchoolYear.start(2026))
        assertEquals(LocalDate.of(2027, 9, 1), SchoolYear.start(2027))
        // 1 September 2029 is a Saturday, 2030 a Sunday.
        assertEquals(LocalDate.of(2029, 9, 3), SchoolYear.start(2029))
        assertEquals(LocalDate.of(2030, 9, 2), SchoolYear.start(2030))
        for (year in 2024..2100) {
            assertTrue(SchoolYear.start(year).dayOfWeek.value <= 5)
        }
    }

    @Test
    fun `the year closes at the end of May`() {
        assertEquals(LocalDate.of(2027, 5, 31), SchoolYear.end(2026))
    }

    @Test
    fun `a date in term belongs to the year that opened before it`() {
        val expected = SchoolYear.start(2026)..SchoolYear.end(2026)
        // 15 October 2026 is the date from the bug report.
        for (day in listOf("2026-09-01", "2026-10-15", "2026-12-31", "2027-01-01", "2027-05-31")) {
            assertEquals(expected, SchoolYear.boundsAt(LocalDate.parse(day)))
        }
    }

    @Test
    fun `the summer belongs to the year about to open`() {
        val expected = SchoolYear.start(2027)..SchoolYear.end(2027)
        for (day in listOf("2027-06-01", "2027-07-20", "2027-08-31")) {
            assertEquals(expected, SchoolYear.boundsAt(LocalDate.parse(day)))
        }
    }

    /**
     * The client asks for the whole year in one request and the server caps the
     * window at 280 days, so the widest year there can be has to fit under it.
     */
    @Test
    fun `no school year is wider than the window the server allows`() {
        val widest = (2024..2100).maxOf { SchoolYear.daysAt(SchoolYear.start(it)) }
        assertEquals(274, widest)
        assertTrue(widest <= 280)
    }
}

package com.lumenpearson.lessons.ui.week

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayOffReason
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolDay
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which accent a day carries, and where one stretch of them ends.
 *
 * The calendar used to answer four different facts with one blank cell: a
 * Saturday with nothing on it, the week between two quarters, the whole of
 * July, and 9 May. Telling them apart is the point, and the run positions are
 * what turn nine consecutive holidays into one bar with the dates on it rather
 * than nine cells that happen to match.
 */
class DayAccentTest {

    private val monday = LocalDate.parse("2026-09-14")

    private fun day(
        date: LocalDate,
        reason: DayOffReason? = null,
        lessons: Int = 0,
        loaded: Boolean = true,
    ) = WeekDayUi(
        date = date,
        day = if (!loaded) {
            null
        } else {
            SchoolDay(
                date = date,
                weekday = date.dayOfWeek.value,
                kind = if (reason != null) DayKind.HOLIDAY else DayKind.NORMAL,
                lessons = (1..lessons).map {
                    Lesson(
                        index = it,
                        subject = "Алгебра",
                        startsAt = LocalTime.of(8, 30),
                        endsAt = LocalTime.of(9, 15),
                    )
                },
                offReason = reason,
            )
        },
        isToday = false,
    )

    @Test
    fun `each reason gets its own accent`() {
        assertEquals(
            DayAccent.PUBLIC_HOLIDAY,
            day(monday, DayOffReason.PUBLIC_HOLIDAY).accent(),
        )
        assertEquals(
            DayAccent.BETWEEN_TERMS,
            day(monday, DayOffReason.BETWEEN_TERMS).accent(),
        )
        assertEquals(DayAccent.OUT_OF_YEAR, day(monday, DayOffReason.OUT_OF_YEAR).accent())
    }

    @Test
    fun `a teaching day is not accented at all`() {
        assertEquals(DayAccent.NONE, day(monday, lessons = 5).accent())
    }

    @Test
    fun `an empty weekend is a weekend and an empty weekday is not`() {
        val saturday = LocalDate.parse("2026-09-19")
        assertEquals(DayAccent.WEEKEND, day(saturday).accent())
        // A Tuesday with no lessons and no reason is a timetable with a gap in
        // it, which is a different thing from a weekend and is not the
        // calendar's to explain.
        assertEquals(DayAccent.NONE, day(LocalDate.parse("2026-09-15")).accent())
    }

    @Test
    fun `a day the server has not sent is not painted as the holidays`() {
        // An unsynced month is «not loaded», and the cells in it are blank for
        // a reason nobody has been told. Painting them would be inventing an
        // answer out of a gap.
        assertEquals(DayAccent.NONE, day(monday, loaded = false).accent())
    }

    @Test
    fun `a stretch of one accent is one run`() {
        val accents = listOf(
            DayAccent.NONE,
            DayAccent.BETWEEN_TERMS,
            DayAccent.BETWEEN_TERMS,
            DayAccent.BETWEEN_TERMS,
            DayAccent.NONE,
        )
        val runs = accents.runPositions()

        assertTrue("the first holiday opens the run", runs[1].first)
        assertFalse(runs[1].last)
        assertFalse("the middle joins on both sides", runs[2].first || runs[2].last)
        assertTrue("the last holiday closes it", runs[3].last)
        assertFalse(runs[3].first)
    }

    @Test
    fun `two different accents side by side do not join`() {
        val runs = listOf(DayAccent.OUT_OF_YEAR, DayAccent.PUBLIC_HOLIDAY).runPositions()
        assertTrue(runs[0].first && runs[0].last)
        assertTrue(runs[1].first && runs[1].last)
    }

    @Test
    fun `ordinary days never join each other`() {
        // Five working days in a row are five days, and a bar drawn across
        // them would say something about the week that is not true.
        val runs = List(5) { DayAccent.NONE }.runPositions()
        assertTrue(runs.all { it.first && it.last })
    }

    @Test
    fun `a run that reaches the end of a week carries on`() {
        // The positions are computed over the whole month, so the cell in the
        // last column of a row is still «middle» — which is what makes the
        // stretch continue on the next line instead of stopping at Sunday.
        val accents = List(9) { DayAccent.OUT_OF_YEAR }
        val runs = accents.runPositions()

        assertTrue(runs.first().first)
        assertTrue(runs.last().last)
        // Index 6 is the last column of the first week; index 7 opens the second.
        assertFalse("Sunday does not close the run", runs[6].last)
        assertFalse("Monday does not reopen it", runs[7].first)
    }
}

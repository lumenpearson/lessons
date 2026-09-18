package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.SchoolClassDto
import com.lumenpearson.lessons.core.model.SchoolYear
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import retrofit2.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app actually asks the server for.
 *
 * The bug this pins: the window was a rolling month anchored to Monday of the
 * current week, so the calendar had a month of real days and then «Нет данных»
 * for the rest of the year — which looks exactly like a timetable that stopped,
 * because an unfetched day and a day with no lessons draw the same.
 *
 * The dates here are the ones from the report: the month grid for October 2026
 * had dots up to the 14th and nothing from the 15th on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncWindowTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun clockAt(day: String): Clock =
        Clock.fixed(LocalDate.parse(day).atStartOfDay(zone).toInstant(), zone)

    private suspend fun windowAt(day: String): Pair<LocalDate, Int> {
        val api = RecordingApi()
        TimetableRepositoryImpl(
            dao = InMemoryTimetableDao(),
            api = api,
            // Any class will do: these tests are about the dates asked for, and
            // the window is the same whichever class is on screen.
            activeClassId = flowOf(1L),
            clock = clockAt(day),
            ioDispatcher = UnconfinedTestDispatcher(),
        ).refresh(days = 31)
        return LocalDate.parse(api.start!!) to api.days!!
    }

    @Test
    fun `mid-term the window is the whole school year`() = runTest {
        val (start, days) = windowAt("2026-10-15")

        assertEquals(LocalDate.of(2026, 9, 1), start)
        // 273, not 274: the widest a year gets is a leap February, not this one.
        assertEquals(273, days)
        // 31 May 2027 is the last day it reaches, ends included.
        assertEquals(LocalDate.of(2027, 5, 31), start.plusDays(days - 1L))
    }

    @Test
    fun `the day from the report is inside the window`() = runTest {
        val (start, days) = windowAt("2026-10-15")
        val last = start.plusDays(days - 1L)

        assertTrue(LocalDate.of(2026, 10, 15) in start..last)
        // And so is every other teaching day of that year, which is the point.
        assertTrue(LocalDate.of(2027, 4, 30) in start..last)
    }

    @Test
    fun `in the first week of September the window still reaches back to Monday`() = runTest {
        // 1 September 2029 is a Saturday, so teaching opens on Monday the 3rd
        // and Monday of the week containing the 5th is that same 3rd.
        val (start, _) = windowAt("2029-09-05")

        assertEquals(SchoolYear.start(2029), start)
    }

    /**
     * In the summer the window is the year about to open, and the Monday anchor
     * is dropped.
     *
     * Keeping it would reach back to a July Monday and ask for some 320 days —
     * past what the server accepts, so the range would come back clipped in
     * April and the last month of the year would be missing. There is nothing
     * to protect in July anyway: the days it would anchor over have no lessons.
     */
    @Test
    fun `in the summer the window is the year about to open`() = runTest {
        val (start, days) = windowAt("2027-07-20")

        assertEquals(SchoolYear.start(2027), start)
        assertEquals(LocalDate.of(2028, 5, 31), start.plusDays(days - 1L))
    }

    @Test
    fun `the window never exceeds what the server accepts`() = runTest {
        for (day in listOf("2026-09-01", "2026-10-15", "2027-05-31", "2027-06-01", "2027-07-20")) {
            val (_, days) = windowAt(day)
            assertTrue("$day asked for $days", days in 1..280)
        }
    }

    private class RecordingApi : LessonsApi by UnusedApi() {
        var start: String? = null
        var days: Int? = null

        override suspend fun bundle(
            start: String,
            days: Int,
            ifNoneMatch: String?,
        ): Response<BundleDto> {
            this.start = start
            this.days = days
            return Response.success(
                BundleDto(
                    apiVersion = 1,
                    schoolClass = SchoolClassDto(id = 1, name = "9А"),
                    days = emptyList(),
                ),
            )
        }
    }

    /** Every call this test does not make; reaching one is the bug. */
    private class UnusedApi : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?) =
            error("unused")
        override suspend fun health() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")
    }

}

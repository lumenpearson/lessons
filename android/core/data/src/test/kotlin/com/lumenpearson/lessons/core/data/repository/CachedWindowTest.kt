package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.HomeworkEntity
import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.database.LessonEntity
import com.lumenpearson.lessons.core.data.database.SchoolClassEntity
import com.lumenpearson.lessons.core.data.database.SchoolDayEntity
import com.lumenpearson.lessons.core.data.database.SchoolDayRecord
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.model.AlertPlanner
import com.lumenpearson.lessons.core.model.AlertPreferences
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a fortnight of the cache instead of a school year, and what that is
 * not allowed to change.
 *
 * `snapshot()` reads every lesson, event and homework row the phone holds —
 * some two hundred days after the sync window became the whole year — and the
 * three callers that are not the calendar all want the next few days: the
 * widget on every tick, the alarm chain on every plan, the change fingerprint
 * on every sync. The bounded twin is the same query with a date range on it.
 *
 * Two things can go wrong with that, and neither shows up on a screen. The
 * bound can be too narrow behind — a window spelled `today .. today + n`
 * loses most of the current week every Sunday — and it can cut off the one
 * answer that is legitimately far away: the first day with lessons after the
 * bound, which is how the first of September is reachable from July and the
 * Monday back from the middle of the winter holidays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedWindowTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    /**
     * The trap, stated first. The Monday of the current week is six days
     * behind a Sunday, and Sunday is the day somebody looks at the week ahead.
     */
    @Test
    fun `the bound reaches back to Monday, which on a Sunday is six days`() {
        val sunday = LocalDate.of(2026, 9, 13)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)

        val bound = CachedWindow.around(sunday)

        assertEquals(LocalDate.of(2026, 9, 7), bound.start)
        assertEquals(LocalDate.of(2026, 9, 21), bound.endInclusive)
    }

    /**
     * And on a Monday the Monday rule alone would stop at today, which is not
     * far enough: an alarm may be delivered half an hour late and publishes
     * the window it was *armed* for, so one armed for Sunday evening and
     * delivered after midnight asks about Sunday.
     */
    @Test
    fun `yesterday is in the bound even when the week started today`() {
        val monday = LocalDate.of(2026, 9, 7)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)

        assertEquals(LocalDate.of(2026, 9, 6), CachedWindow.around(monday).start)
    }

    @Test
    fun `the bounded read stops at the bound and the whole-year read does not`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val dao = cacheOfTheYear()
        val repository = repository(dao, today)

        val bounded = repository.snapshotAroundToday()!!
        val bound = CachedWindow.around(today)

        assertTrue(bounded.days.isNotEmpty())
        assertTrue(bounded.days.all { it.date in bound })
        assertEquals(
            "a fortnight, not a year",
            true,
            bounded.days.size < repository.snapshot()!!.days.size / 10,
        )
    }

    /**
     * The cost, in the only unit that matters: each day row drags its lessons,
     * its events and its homework with it.
     */
    @Test
    fun `the bounded read is the whole point - it reads a fortnight of rows`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val dao = cacheOfTheYear()
        val repository = repository(dao, today)

        repository.snapshotAroundToday()
        val bounded = dao.dayRowsRead
        repository.snapshot()
        val whole = dao.dayRowsRead - bounded

        assertTrue("read $bounded rows for a fortnight", bounded <= 16)
        assertTrue("the year is $whole rows, and it was all of them", whole > 150)
    }

    /**
     * The hard part. In July the cache holds the year that is about to open,
     * every day of it is outside any bound around today, and «when is the next
     * school day» still has an answer — the phone shows it on the home screen
     * all summer.
     */
    @Test
    fun `the first of September is reachable from the middle of July`() = runTest {
        val today = LocalDate.of(2026, 7, 15)
        val dao = cacheOfTheYear()
        val repository = repository(dao, today)

        val bounded = repository.snapshotAroundToday()!!

        assertEquals("nothing in the bound, which is correct", emptyList<Any>(), bounded.days)
        assertEquals(
            repository.snapshot()!!.schoolDayAfter(today)?.date,
            bounded.schoolDayAfter(today)?.date,
        )
        assertEquals(LocalDate.of(2026, 9, 1), bounded.schoolDayAfter(today)?.date)
    }

    /**
     * The same question from inside a fortnight-long holiday, which is the
     * shape that has already cost this app a silent first morning back once.
     */
    @Test
    fun `a holiday wider than the bound does not hide the Monday back`() = runTest {
        val today = LocalDate.of(2026, 12, 30)
        val dao = cacheOfTheYear()
        val repository = repository(dao, today)

        val bounded = repository.snapshotAroundToday()!!

        assertEquals(LocalDate.of(2027, 1, 11), bounded.schoolDayAfter(today)?.date)
        assertEquals(
            repository.snapshot()!!.schoolDayAfter(today)?.date,
            bounded.schoolDayAfter(today)?.date,
        )
    }

    /**
     * «Has lessons» means lessons that take place. The bounded read answers
     * this one in SQL and `Timetable.schoolDayAfter` answers it in Kotlin, so
     * the two definitions have to be the same definition — a day whose whole
     * timetable was struck out is not the next school day, and pointing the
     * home screen at it would promise a day that is not happening.
     */
    @Test
    fun `a day whose lessons are all cancelled is not the next school day`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val bound = CachedWindow.around(today)
        val struckOut = bound.endInclusive.plusDays(1)
        val real = bound.endInclusive.plusDays(2)
        val dao = InMemoryTimetableDao()
        dao.replaceAll(
            classRow(),
            listOf(
                schoolDay(struckOut, cancelled = true),
                schoolDay(real),
            ),
            null,
        )
        val repository = repository(dao, today)

        val bounded = repository.snapshotAroundToday()!!

        assertEquals(real, bounded.schoolDayAfter(today)?.date)
        assertEquals(
            repository.snapshot()!!.schoolDayAfter(today)?.date,
            bounded.schoolDayAfter(today)?.date,
        )
    }

    /**
     * The stored lookahead day is still the fallback.
     *
     * It is what the server resolves past the end of the synced window, so it
     * is the only answer there is once the cache itself has run out — and the
     * bounded read must not lose it by looking only for a day of its own.
     */
    @Test
    fun `past the end of the cache the lookahead day is what answers`() = runTest {
        val today = LocalDate.of(2027, 5, 27)
        val lastDay = LocalDate.of(2027, 5, 28)
        val september = LocalDate.of(2027, 9, 1)
        val dao = InMemoryTimetableDao()
        dao.replaceAll(classRow(), listOf(schoolDay(lastDay)), schoolDay(september))
        val repository = repository(dao, today)

        val bounded = repository.snapshotAroundToday()!!

        assertEquals(lastDay, bounded.schoolDayAfter(today)?.date)
        assertEquals(september, bounded.schoolDayAfter(lastDay)?.date)
    }

    /**
     * What the bound is actually for, checked against the thing that reads it.
     *
     * `AlertPlanner` looks a week and a day ahead and its horizon is private
     * to another module, so nothing can import it and assert on the number.
     * This walks the planner over both readings instead: if the bound ever
     * stops covering what the planner reaches for, the two answers part
     * company here rather than in a notification nobody gets.
     */
    @Test
    fun `the planner sees the same thing through the bound as through the year`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val dao = cacheOfTheYear()
        val repository = repository(dao, today)
        val whole = repository.snapshot()!!
        val bounded = repository.snapshotAroundToday()!!
        val preferences = AlertPreferences(
            lessonSoon = true,
            morningSummary = true,
            homeworkReminder = true,
        )

        // Every hour of a day and a night, so an answer that differs by the
        // date the planner starts from cannot slip through between samples.
        for (hour in 0 until 24) {
            val after = today.atTime(hour, 0)
            assertEquals(
                "at $after",
                AlertPlanner.next(whole, preferences, after),
                AlertPlanner.next(bounded, preferences, after),
            )
        }
    }

    /**
     * The same walk, anchored where it can actually go wrong.
     *
     * The test above compares two readings of an ordinary week, and an
     * ordinary week's next alert is tomorrow morning — which a bound half the
     * width would still contain. The number is only load-bearing at the far
     * edge of the planner's reach: eight days before the first lesson back
     * from the winter holidays, the whole year can just see it and a bound one
     * day short cannot, and the difference is the morning nobody is woken for.
     */
    @Test
    fun `the far edge of the planner's reach is inside the bound`() = runTest {
        val today = LocalDate.of(2027, 1, 4)
        val backToSchool = LocalDate.of(2027, 1, 11)
        val dao = cacheOfTheYear()
        val repository = repository(dao, today)
        val preferences = AlertPreferences(
            lessonSoon = true,
            morningSummary = true,
            homeworkReminder = true,
        )

        val whole = AlertPlanner.next(repository.snapshot()!!, preferences, today.atStartOfDay())
        val bounded =
            AlertPlanner.next(repository.snapshotAroundToday()!!, preferences, today.atStartOfDay())

        assertEquals(
            "the year sees the first morning back from here",
            backToSchool,
            whole?.at?.toLocalDate(),
        )
        assertEquals("and so must the fortnight", whole, bounded)
    }

    /** Nothing cached is nothing cached, whichever way it is asked. */
    @Test
    fun `no class row means no bounded snapshot either`() = runTest {
        val repository = repository(InMemoryTimetableDao(), LocalDate.of(2026, 9, 16))

        assertNull(repository.snapshotAroundToday())
    }

    // -- builders -----------------------------------------------------------

    private fun repository(dao: InMemoryTimetableDao, today: LocalDate) = TimetableRepositoryImpl(
        dao = dao,
        api = UnusedLessonsApi(),
        activeClassId = MutableStateFlow(CLASS_ID),
        // Noon, so the school's date is the same date whichever side of
        // midnight the zone conversion lands on.
        clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /**
     * A school year as the sync actually caches it: every weekday from the
     * first of September to the end of May, with the winter holidays cut out
     * of it.
     */
    private suspend fun cacheOfTheYear(): InMemoryTimetableDao {
        val dao = InMemoryTimetableDao()
        val holidayFrom = LocalDate.of(2026, 12, 29)
        val holidayTo = LocalDate.of(2027, 1, 10)
        val days = generateSequence(LocalDate.of(2026, 9, 1)) { it.plusDays(1) }
            .takeWhile { it <= LocalDate.of(2027, 5, 29) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .filterNot { it in holidayFrom..holidayTo }
            .map { schoolDay(it) }
            .toList()
        dao.replaceAll(classRow(), days, null)
        return dao
    }

    private fun classRow() = SchoolClassEntity(
        id = CLASS_ID,
        name = "9А",
        school = null,
        timeZoneId = zone.id,
        syncedAtEpochMillis = 0L,
    )

    private fun schoolDay(date: LocalDate, cancelled: Boolean = false) = SchoolDayRecord(
        day = SchoolDayEntity(
            classId = CLASS_ID,
            date = date,
            weekday = date.dayOfWeek.value,
            kind = "NORMAL",
            note = null,
        ),
        lessons = listOf(
            LessonEntity(
                dayId = 0L,
                index = 1,
                subject = "Алгебра",
                startsAt = LocalTime.of(8, 30),
                endsAt = LocalTime.of(9, 15),
                room = null,
                teacher = null,
                colorHex = null,
                isReplaced = false,
                isCancelled = cancelled,
                note = null,
            ),
        ),
        // Homework on every day, because the reminder is the one rule that
        // asks about the day *after* the one it is planning on — which is how
        // the last day the bound covers reaches past its own edge.
        homework = listOf(
            HomeworkEntity(
                dayId = 0L,
                subject = "Алгебра",
                text = "§12",
                attachmentUrl = null,
            ),
        ),
    )

    /** Reaching the network at all would mean a read went past the cache. */
    private class UnusedLessonsApi : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?) =
            error("unused")
        override suspend fun health() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")
    }

    private companion object {
        const val CLASS_ID = 1L
    }
}

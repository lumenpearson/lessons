package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.database.LessonEntity
import com.lumenpearson.lessons.core.data.database.SchoolClassEntity
import com.lumenpearson.lessons.core.data.database.SchoolDayEntity
import com.lumenpearson.lessons.core.data.database.SchoolDayRecord
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One phone, two classes, one cache.
 *
 * The app holds a membership per class and shows one at a time, so the cache
 * holds a window per class side by side. That arrangement has exactly one way
 * to go wrong, and it is a quiet one: both windows are a plausible school week,
 * so a read that loses its class filter — or a write that wipes more than its
 * own class — draws something that looks entirely correct and belongs to
 * somebody else's timetable. Nothing on screen would say so.
 *
 * These tests drive the real [com.lumenpearson.lessons.core.data.database.TimetableDao]
 * orchestration through an in-memory store: `replaceAll`, `clear` and
 * `clearAll` are concrete on the DAO, so what is asserted here is the shipped
 * wipe order and the shipped subqueries, not a second copy of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassScopedCacheTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    @Test
    fun `two classes cached together keep their own days`() = runTest {
        val dao = InMemoryTimetableDao()
        dao.replaceAll(classRow(1, "7А"), listOf(day(1, monday, "Алгебра")), null)
        dao.replaceAll(classRow(2, "9Б"), listOf(day(2, monday, "История")), null)

        assertEquals(listOf(1L, 2L), dao.cachedClassIds())
        assertEquals("Алгебра", dao.days(1).single().lessons.single().subject)
        assertEquals("История", dao.days(2).single().lessons.single().subject)
    }

    @Test
    fun `syncing one class does not empty the other`() = runTest {
        val dao = InMemoryTimetableDao()
        dao.replaceAll(classRow(1, "7А"), listOf(day(1, monday, "Алгебра")), null)
        dao.replaceAll(classRow(2, "9Б"), listOf(day(2, monday, "История")), null)

        // The sync that used to wipe the table. 7«А» gets a new window; 9«Б»
        // is not being synced and must come out of it untouched.
        dao.replaceAll(
            classRow(1, "7А"),
            listOf(day(1, monday, "Геометрия"), day(1, monday.plusDays(1), "Физика")),
            null,
        )

        assertEquals(2, dao.dayCountOf(1))
        assertEquals("Геометрия", dao.days(1).first().lessons.single().subject)
        assertEquals(1, dao.dayCountOf(2))
        assertEquals("История", dao.days(2).single().lessons.single().subject)
    }

    @Test
    fun `leaving one class does not empty the other`() = runTest {
        val dao = InMemoryTimetableDao()
        dao.replaceAll(classRow(1, "7А"), listOf(day(1, monday, "Алгебра")), null)
        dao.replaceAll(classRow(2, "9Б"), listOf(day(2, monday, "История")), null)

        dao.clear(1)

        assertEquals(listOf(2L), dao.cachedClassIds())
        assertEquals(0, dao.dayCountOf(1))
        assertEquals(0, dao.lessonCountOf(1))
        assertEquals(1, dao.dayCountOf(2))
        // The children hang off the day rather than off the class, so the
        // subquery is the only thing keeping them apart. Deleting by class
        // without it takes every lesson on the phone.
        assertEquals(1, dao.lessonCountOf(2))
    }

    @Test
    fun `signing out of everything empties the cache`() = runTest {
        val dao = InMemoryTimetableDao()
        dao.replaceAll(classRow(1, "7А"), listOf(day(1, monday, "Алгебра")), null)
        dao.replaceAll(classRow(2, "9Б"), listOf(day(2, monday, "История")), null)

        dao.clearAll()

        assertEquals(emptyList<Long>(), dao.cachedClassIds())
        assertEquals(0, dao.dayCountOf(1))
        assertEquals(0, dao.dayCountOf(2))
    }

    @Test
    fun `a record built for the wrong class is filed under the class row`() = runTest {
        val dao = InMemoryTimetableDao()

        // The day says class 2; the class row being written says class 1. The
        // row is the authority, so this lands in 1 and 2 stays empty — which is
        // what stops a switch mid-sync from filing one class's week under the
        // other's name.
        dao.replaceAll(classRow(1, "7А"), listOf(day(2, monday, "Алгебра")), null)

        assertEquals(1, dao.dayCountOf(1))
        assertEquals(0, dao.dayCountOf(2))
    }

    @Test
    fun `the timetable on screen follows the class that is selected`() = runTest {
        val dao = InMemoryTimetableDao()
        dao.replaceAll(classRow(1, "7А"), listOf(day(1, monday, "Алгебра")), null)
        dao.replaceAll(classRow(2, "9Б"), listOf(day(2, monday, "История")), null)

        val active = MutableStateFlow<Long?>(1L)
        val repository = TimetableRepositoryImpl(
            dao = dao,
            api = UnusedLessonsApi(),
            activeClassId = active,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals("7А", repository.timetable.first()?.schoolClass?.name)
        assertEquals("Алгебра", repository.snapshot()?.days?.single()?.lessons?.single()?.subject)

        active.value = 2L

        assertEquals("9Б", repository.timetable.first()?.schoolClass?.name)
        assertEquals("История", repository.snapshot()?.days?.single()?.lessons?.single()?.subject)

        // No class on screen is not "show the first one you find": it is the
        // state the join screen is drawn for.
        active.value = null

        assertNull(repository.timetable.first())
        assertNull(repository.snapshot())
    }

    // -- builders -----------------------------------------------------------

    private fun classRow(id: Long, name: String) = SchoolClassEntity(
        id = id,
        name = name,
        school = null,
        timeZoneId = "Europe/Moscow",
        syncedAtEpochMillis = 0L,
    )

    private fun day(classId: Long, date: LocalDate, subject: String) = SchoolDayRecord(
        day = SchoolDayEntity(
            classId = classId,
            date = date,
            weekday = date.dayOfWeek.value,
            kind = "NORMAL",
            note = null,
        ),
        lessons = listOf(
            LessonEntity(
                dayId = 0L,
                index = 1,
                subject = subject,
                startsAt = LocalTime.of(8, 30),
                endsAt = LocalTime.of(9, 15),
                room = null,
                teacher = null,
                colorHex = null,
                isReplaced = false,
                isCancelled = false,
                note = null,
            ),
        ),
    )

    /** Reaching the network at all would mean a read went past the cache. */
    private class UnusedLessonsApi : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun bundle(start: String, days: Int) = error("unused")
        override suspend fun health() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")
    }
}

package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.network.dto.DiaryEditDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline diary, as the repository fills it and the screens read it.
 *
 * Driven through the real `DiaryRepositoryImpl` and the real `DiaryCacheImpl`
 * over an in-memory DAO, so what is under test is the write-through as it
 * ships: which answers are kept, under which week, and what «loaded» means.
 */
class DiaryCacheTest {

    private val monday = LocalDate.parse("2026-09-21")
    private val sunday = LocalDate.parse("2026-09-27")

    @Test
    fun `a week fetched empty is loaded, a week never fetched is not`() = runTest {
        val rig = DiaryRig(api = ScriptedDiaryApi(lessons = emptyList()))

        rig.repository.schedule(1, monday, sunday)

        val fetched = rig.cache.week(1, monday).first()
        assertNotNull(fetched.lessonsLoadedAt)
        assertTrue(fetched.lessons.isEmpty())
        // The homework half of the same week was never asked: «не загружено»,
        // not «нет заданий».
        assertNull(fetched.homeworkLoadedAt)

        val never = rig.cache.week(1, monday.plusWeeks(1)).first()
        assertNull(never.lessonsLoadedAt)
    }

    @Test
    fun `two weeks in one read stamp both weeks and file each lesson under its own`() = runTest {
        val rig = DiaryRig(
            api = ScriptedDiaryApi(lessons = listOf(lesson("2026-09-22"), lesson("2026-09-29", "Physics"))),
        )

        rig.repository.schedule(1, monday, monday.plusDays(13))

        val first = rig.cache.week(1, monday).first()
        val second = rig.cache.week(1, monday.plusWeeks(1)).first()
        assertEquals(listOf("Algebra"), first.lessons.map { it.subject })
        assertEquals(listOf("Physics"), second.lessons.map { it.subject })
        assertNotNull(first.lessonsLoadedAt)
        assertNotNull(second.lessonsLoadedAt)
    }

    /**
     * A range that is not whole weeks would stamp a week fetched when only part
     * of it was, and its missing days would be drawn as empty.
     */
    @Test
    fun `a range that is not whole weeks passes through uncached`() = runTest {
        val rig = DiaryRig(api = ScriptedDiaryApi(lessons = listOf(lesson("2026-09-22"))))

        val result = rig.repository.schedule(1, monday.plusDays(1), sunday)

        assertEquals(1, result.getOrThrow().size)
        assertEquals(0, rig.dao.rowCount)
    }

    @Test
    fun `a new read of a week replaces the old one`() = runTest {
        val api = ScriptedDiaryApi(lessons = listOf(lesson("2026-09-22"), lesson("2026-09-23")))
        val rig = DiaryRig(api = api)
        rig.repository.schedule(1, monday, sunday)

        api.lessons = listOf(lesson("2026-09-24", "History"))
        rig.repository.schedule(1, monday, sunday)

        assertEquals(listOf("History"), rig.cache.week(1, monday).first().lessons.map { it.subject })
    }

    @Test
    fun `corrections and times survive the round trip`() = runTest {
        val corrected = DiaryLessonDto(
            date = "2026-09-22",
            number = 2,
            subject = "Algebra",
            startsAt = "09:00",
            endsAt = "09:45",
            room = "301",
            target = "2026-09-22|2|Algebra",
            edits = listOf(DiaryEditDto(field = "room", value = "301", original = "204", changedUpstream = true)),
            ambiguous = true,
        )
        val rig = DiaryRig(api = ScriptedDiaryApi(lessons = listOf(corrected)))

        val live = rig.repository.schedule(1, monday, sunday).getOrThrow()

        assertEquals(live, rig.cache.week(1, monday).first().lessons)
    }

    @Test
    fun `the marks window read is the one kept, and covers says so`() = runTest {
        val rig = DiaryRig(api = ScriptedDiaryApi(marks = listOf(mark("2026-09-10"), mark("2026-09-11", "4"))))
        val window = DiaryDateRange(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-25"))
        rig.repository.students()

        rig.repository.grades(1, window.from, window.to)

        val kept = rig.cache.marks(1).first()
        assertEquals(listOf("5", "4"), kept.marks.map { it.value })
        assertTrue(kept.covers(window))
        assertFalse(kept.covers(window.copy(from = window.from.plusDays(1))))
    }

    /**
     * A pupil still listed keeps the stamps of what was read for them; a
     * pupil no longer listed takes every row of theirs along.
     */
    @Test
    fun `a new pupil list keeps what was read for pupils still on it and drops the rest`() = runTest {
        val api = ScriptedDiaryApi(
            students = listOf(student(1), student(2)),
            lessons = listOf(lesson("2026-09-22")),
            marks = listOf(mark("2026-09-10")),
        )
        val rig = DiaryRig(api = api)
        rig.repository.students()
        rig.repository.grades(1, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-25"))
        rig.repository.schedule(2, monday, sunday)

        api.students = listOf(student(1))
        rig.repository.students()

        assertNotNull(rig.cache.marks(1).first().loadedAt)
        assertEquals(listOf(1L), rig.cache.students.first().students.map { it.id })
        assertTrue(rig.dao.lessons.none { it.studentId == 2L })
        assertTrue(rig.dao.weeks.none { it.studentId == 2L })
    }

    @Test
    fun `the terms are kept per pupil with when they were read`() = runTest {
        val rig = DiaryRig(
            api = ScriptedDiaryApi(
                periods = listOf(
                    DiaryPeriodDto(id = 10, name = "I", startsOn = "2026-09-01", endsOn = "2026-10-26"),
                    DiaryPeriodDto(id = 11, name = "II", startsOn = "2026-11-05", endsOn = "2026-12-28", isCurrent = true),
                ),
            ),
        )
        rig.repository.students()

        rig.repository.periods(1)

        val kept = rig.cache.periods(1).first()
        assertEquals(listOf(10L, 11L), kept.periods.map { it.id })
        assertEquals(11L, kept.periods.single { it.isCurrent }.id)
        assertNotNull(kept.loadedAt)
    }

    @Test
    fun `sign-out empties every table even offline`() = runTest {
        val api = ScriptedDiaryApi(
            lessons = listOf(lesson("2026-09-22")),
            homework = listOf(homeworkDue("2026-09-23")),
            marks = listOf(mark("2026-09-10")),
            periods = listOf(DiaryPeriodDto(id = 10, name = "I")),
        )
        val rig = DiaryRig(api = api)
        rig.repository.students()
        rig.repository.periods(1)
        rig.repository.schedule(1, monday, sunday)
        rig.repository.homework(1, monday, sunday)
        rig.repository.grades(1, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-25"))
        assertTrue(rig.dao.rowCount > 0)
        api.failures["logout"] = IOException("no network")

        val result = rig.repository.signOut()

        assertTrue(result.isSuccess)
        assertEquals(0, rig.dao.rowCount)
    }

    /**
     * The answer to a read that was out when somebody signed out belongs to an
     * account the phone has let go of. Written, it would leave a child's week
     * on the phone after the screen had said it was gone.
     */
    @Test
    fun `a read that lands after a sign-out is not kept`() = runTest {
        val api = ScriptedDiaryApi(lessons = listOf(lesson("2026-09-22")))
        val rig = DiaryRig(api = api)
        api.duringRequest = { route -> if (route.startsWith("schedule")) rig.cache.clear() }

        val result = rig.repository.schedule(1, monday, sunday)

        assertTrue(result.isSuccess)
        assertEquals(0, rig.dao.rowCount)
    }

    @Test
    fun `a cache that cannot be written costs the copy and not the answer`() = runTest {
        val rig = DiaryRig(api = ScriptedDiaryApi(lessons = listOf(lesson("2026-09-22"))))
        val broken = object : DiaryCacheWriter by rig.cache {
            override suspend fun putLessons(
                generation: Long,
                studentId: Long,
                from: LocalDate,
                to: LocalDate,
                rows: List<com.lumenpearson.lessons.core.data.repository.DiaryLesson>,
            ) = throw IOException("no space left on device")
        }
        val repository = com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl(
            api = rig.api,
            store = rig.store,
            diarySignIn = UnusedSignIn,
            cache = broken,
            ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )

        assertEquals(1, repository.schedule(1, monday, sunday).getOrThrow().size)
    }

    @Test
    fun `a failed read writes nothing`() = runTest {
        val api = ScriptedDiaryApi(lessons = listOf(lesson("2026-09-22")))
        api.failures["schedule"] = statusError(503)
        val rig = DiaryRig(api = api)

        assertTrue(rig.repository.schedule(1, monday, sunday).isFailure)
        assertEquals(0, rig.dao.rowCount)
    }

    @Test
    fun `an unreadable corrections column costs the marks, not the lesson`() {
        assertEquals(emptyList<Any>(), DiaryEditsCodec.decode("not json"))
        assertEquals(emptyList<Any>(), DiaryEditsCodec.decode(""))
        assertEquals("", DiaryEditsCodec.encode(emptyList()))
    }
}

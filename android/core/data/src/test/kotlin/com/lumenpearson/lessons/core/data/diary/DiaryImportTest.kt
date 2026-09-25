package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.HOMEWORK
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.MARKS
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.PERIODS
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.SCHEDULE
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.STUDENTS
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The import, over the real repository and the real cache.
 *
 * The clock is Friday 25 September 2026, 13:00 in Moscow, so the plan is the
 * week of Monday 21 September and the next: 2026-09-21 .. 2026-10-04.
 */
class DiaryImportTest {

    private val weeks = "2026-09-21..2026-10-04"

    private val neverAsked: suspend (List<DiaryStudent>) -> DiaryStudent = {
        fail("the chooser was not supposed to be shown")
        error("unreachable")
    }

    @Test
    fun `phases arrive in order and the fraction never goes backwards`() = runTest {
        val rig = DiaryRig()

        val progress = rig.import.run(choose = neverAsked).toList()

        val phases = progress.filterIsInstance<DiaryImportProgress.Running>().map { it.phase }
        assertEquals(listOf(STUDENTS, PERIODS, SCHEDULE, HOMEWORK, MARKS), phases)
        val fractions = progress.flatMap { state ->
            when (state) {
                is DiaryImportProgress.Running -> listOf(state.completed, state.target)
                else -> listOf(state.completed)
            }
        }
        assertEquals(fractions.sorted(), fractions)
        assertEquals(1f, progress.last().completed)
        assertTrue(progress.last() is DiaryImportProgress.Done)
    }

    @Test
    fun `requests are sequential and within the plan`() = runTest {
        val rig = DiaryRig()

        rig.import.run(choose = neverAsked).toList()

        assertEquals(
            listOf(
                "students",
                "periods 1",
                "schedule 1 $weeks",
                "homework 1 $weeks",
                // No current term: the last 62 days, which is what the marks
                // tab asks for without one.
                "grades 1 2026-07-25..2026-09-25",
            ),
            rig.api.calls,
        )
    }

    @Test
    fun `the marks window is the current term's, as the marks tab asks for it`() = runTest {
        val api = ScriptedDiaryApi(
            periods = listOf(DiaryPeriodDto(id = 7, name = "I", startsOn = "2026-09-01", endsOn = "2026-10-26", isCurrent = true)),
        )
        val rig = DiaryRig(api = api)

        rig.import.run(choose = neverAsked).toList()

        assertEquals("grades 1 2026-09-01..2026-09-25", rig.api.calls.last())
        assertTrue(rig.cache.marks(1).first().covers(DiaryWindows.gradeWindow(rig.clock.today(), rig.cache.periods(1).first().periods.single())))
    }

    @Test
    fun `one pupil is chosen silently, two ask, a stored choice is kept`() = runTest {
        val one = DiaryRig()
        val done = one.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Done
        assertEquals(1L, done.student.id)
        assertEquals(1L, one.store.student)

        val two = DiaryRig(api = ScriptedDiaryApi(students = listOf(student(1), student(2))))
        var offered: List<Long> = emptyList()
        val progress = two.import.run(choose = { students ->
            offered = students.map { it.id }
            students.last()
        }).toList()
        assertEquals(listOf(1L, 2L), offered)
        assertTrue(progress.any { it is DiaryImportProgress.ChoosingStudent })
        assertEquals(2L, two.store.student)
        assertEquals(2L, two.cache.selectedStudentId.first())
        assertTrue(two.api.calls.contains("schedule 2 $weeks"))

        val remembered = DiaryRig(
            api = ScriptedDiaryApi(students = listOf(student(1), student(2))),
            store = MemoryDiaryStore(student = 2),
        )
        val kept = remembered.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Done
        assertEquals(2L, kept.student.id)
    }

    /** The registration already answered with the pupils; asking again is an upstream call for nothing. */
    @Test
    fun `pupils the registration just stored are not asked for again`() = runTest {
        val rig = DiaryRig()
        rig.cache.putStudents(rig.cache.generation(), listOf(pupil(1)))

        rig.import.run(choose = neverAsked).toList()

        assertFalse(rig.api.calls.contains("students"))
    }

    @Test
    fun `pupils stored long ago are asked for again`() = runTest {
        val rig = DiaryRig()
        rig.cache.putStudents(rig.cache.generation(), listOf(pupil(1)))
        rig.clock.now = rig.clock.now.plus(DiaryFreshFor).plusSeconds(1)

        rig.import.run(choose = neverAsked).toList()

        assertEquals("students", rig.api.calls.first())
    }

    @Test
    fun `failed marks or homework finish Done with skipped, failed schedule fails`() = runTest {
        val soft = DiaryRig()
        soft.api.failures["homework"] = statusError(503)
        soft.api.failures["grades"] = IOException("no network")
        soft.api.failures["periods"] = statusError(502)
        val done = soft.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Done
        assertEquals(setOf(PERIODS, HOMEWORK, MARKS), done.skipped)
        // The homework half of the weeks stays «не загружено», not «нет заданий».
        assertEquals(null, soft.cache.week(1, soft.clock.monday()).first().homeworkLoadedAt)

        val hard = DiaryRig()
        hard.api.failures["schedule"] = statusError(503)
        val failed = hard.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Failed
        assertEquals(SCHEDULE, failed.phase)
        assertTrue(failed.resumable)
        assertFalse(hard.api.calls.any { it.startsWith("homework") || it.startsWith("grades") })
    }

    @Test
    fun `resume starts at the failed phase and does not ask again for what landed`() = runTest {
        val rig = DiaryRig()
        rig.api.failures["schedule"] = statusError(503)
        val failed = rig.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Failed
        rig.api.failures.clear()
        rig.api.calls.clear()

        val progress = rig.import.run(resumeFrom = failed.phase, choose = neverAsked).toList()

        assertTrue(progress.last() is DiaryImportProgress.Done)
        assertEquals(listOf("schedule 1 $weeks", "homework 1 $weeks"), rig.api.calls.take(2))
        assertFalse(rig.api.calls.contains("students"))
        assertFalse(rig.api.calls.any { it.startsWith("periods") })
    }

    @Test
    fun `re-auth mid-import surfaces as ReauthRequired and stops there`() = runTest {
        val rig = DiaryRig()
        rig.api.failures["homework"] = statusError(401, DiaryFailure.REAUTH_HEADER to "required")

        val failed = rig.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Failed

        assertEquals(HOMEWORK, failed.phase)
        assertEquals(DiarySignInProblem.ReauthRequired, failed.problem)
        assertTrue(failed.resumable)
        assertFalse(rig.api.calls.any { it.startsWith("grades") })
    }

    @Test
    fun `a dead bearer stops the import and cannot be resumed`() = runTest {
        val rig = DiaryRig()
        rig.api.failures["periods"] = statusError(401)

        val failed = rig.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Failed

        assertEquals(DiarySignInProblem.SignInRequired, failed.problem)
        assertFalse(failed.resumable)
    }

    @Test
    fun `no session is a sign-in, and nothing is asked`() = runTest {
        val rig = DiaryRig(store = MemoryDiaryStore(session = null))

        val failed = rig.import.run(choose = neverAsked).toList().single() as DiaryImportProgress.Failed

        assertEquals(DiarySignInProblem.SignInRequired, failed.problem)
        assertTrue(rig.api.calls.isEmpty())
    }

    @Test
    fun `an account with no pupil is NoStudent`() = runTest {
        val rig = DiaryRig(api = ScriptedDiaryApi(students = emptyList()))

        val failed = rig.import.run(choose = neverAsked).toList().last() as DiaryImportProgress.Failed

        assertEquals(DiarySignInProblem.NoStudent, failed.problem)
        assertFalse(failed.resumable)
    }

    /**
     * 19:30 UTC on Sunday 27 September is 02:30 on Monday in Tomsk (UTC+7) and
     * still 22:30 on Sunday in Moscow (UTC+3): the one kind of instant at which
     * the diary's zone and the zone that used to be hard-coded here disagree.
     * An instant both call Monday would pass with either.
     */
    private val tomskMondayMoscowSunday = java.time.Instant.parse("2026-09-27T19:30:00Z")

    private val tomskSession = DiarySession(
        login = "parent",
        token = "ours",
        target = DiaryTarget.netschool("tomsk", 5, null, "parent", "Asia/Tomsk"),
    )

    @Test
    fun `the weeks are cut in the diary's zone, not the phone's`() = runTest {
        val rig = DiaryRig(store = MemoryDiaryStore(session = tomskSession))
        rig.clock.now = tomskMondayMoscowSunday

        rig.import.run(choose = neverAsked).toList()

        assertTrue(rig.api.calls.toString(), rig.api.calls.contains("schedule 1 2026-09-28..2026-10-11"))
        // Moscow's Sunday would have fetched the fortnight before.
        assertFalse(rig.api.calls.toString(), rig.api.calls.contains("schedule 1 2026-09-21..2026-10-04"))
    }

    @Test
    fun `refreshIfStale cuts the weeks in the diary's zone too`() = runTest {
        val rig = DiaryRig(store = MemoryDiaryStore(session = tomskSession, student = 1))
        rig.clock.now = tomskMondayMoscowSunday

        assertEquals(true, rig.import.refreshIfStale().getOrThrow())

        assertEquals(
            listOf("schedule 1 2026-09-28..2026-10-11", "homework 1 2026-09-28..2026-10-11"),
            rig.api.calls,
        )
    }

    @Test
    fun `refreshIfStale reads this week and the next when they are old, and only then`() = runTest {
        val rig = DiaryRig()
        rig.import.run(choose = neverAsked).toList()
        rig.api.calls.clear()

        assertEquals(false, rig.import.refreshIfStale().getOrThrow())
        assertTrue(rig.api.calls.isEmpty())

        rig.clock.now = rig.clock.now.plus(DiaryFreshFor).plus(Duration.ofMinutes(1))
        assertEquals(true, rig.import.refreshIfStale().getOrThrow())
        assertEquals(listOf("schedule 1 $weeks", "homework 1 $weeks"), rig.api.calls)
    }

    @Test
    fun `refreshIfStale without a session asks nothing`() = runTest {
        val rig = DiaryRig(store = MemoryDiaryStore(session = null, student = 1))

        assertEquals(false, rig.import.refreshIfStale().getOrThrow())
        assertTrue(rig.api.calls.isEmpty())
    }

    @Test
    fun `refreshIfStale reports a re-auth rather than hiding it`() = runTest {
        val rig = DiaryRig(store = MemoryDiaryStore(student = 1))
        rig.api.failures["schedule"] = statusError(401, DiaryFailure.REAUTH_HEADER to "required")

        val result = rig.import.refreshIfStale()

        assertEquals(DiarySignInProblem.ReauthRequired, result.exceptionOrNull())
    }

    /**
     * Asked while the import's own schedule request is in flight — a stored
     * pupil, and a week not yet stamped — so a refresh that ignored the import
     * would get as far as a second schedule request. Asked any earlier (while
     * the pupil is still being chosen) it returns `false` for want of a pupil,
     * lock or no lock.
     */
    @Test
    fun `refreshIfStale is a no-op while an import is running`() = runTest {
        val rig = DiaryRig(store = MemoryDiaryStore(student = 1))
        var asked = false
        var during: Result<Boolean>? = null
        rig.api.duringRequest = { route ->
            // Once: a refresh that did get through sends a schedule request of
            // its own, which comes back through here.
            if (route.startsWith("schedule") && !asked) {
                asked = true
                during = rig.import.refreshIfStale()
            }
        }

        val last = rig.import.run(choose = neverAsked).toList().last()

        assertTrue(last is DiaryImportProgress.Done)
        assertTrue(asked)
        assertEquals(false, during?.getOrThrow())
        assertEquals(listOf("schedule 1 $weeks"), rig.api.calls.filter { it.startsWith("schedule") })
        assertEquals(listOf("homework 1 $weeks"), rig.api.calls.filter { it.startsWith("homework") })
    }

    /**
     * The container's sign-in seeds the pupils the registration answered, into
     * a cache the sign-in has just emptied for a new account — which is exactly
     * where taking the generation before the registration, or seeding nothing,
     * would leave the import asking for the pupils again.
     */
    @Test
    fun `the pupils a sign-in registered are seeded after it empties the cache, and not asked for again`() = runTest {
        for (path in listOf("signIn", "register")) {
            val rig = DiaryRig()
            // What the last account left, which a new account's sign-in empties.
            rig.cache.putStudents(rig.cache.generation(), listOf(pupil(9)))
            val seeding = SeedingDiarySignIn(ClearingSignIn(rig), rig.cache)

            when (path) {
                "signIn" -> seeding.signIn(DiaryTarget.petersburg("parent"), "secret").getOrThrow()
                else -> seeding.register(
                    com.lumenpearson.lessons.core.data.upstream.UpstreamSession.Petersburg(
                        target = DiaryTarget.petersburg("parent"),
                        token = "never read",
                        openedAt = rig.clock.now,
                    ),
                ).getOrThrow()
            }
            assertEquals(path, listOf(1L), rig.cache.students.first().students.map { it.id })

            rig.import.run(choose = neverAsked).toList()

            assertFalse(path, rig.api.calls.contains("students"))
        }
    }

    /** A sign-in that does what [com.lumenpearson.lessons.core.data.repository.DiarySignInImpl] does for a new account: empties the cache, then answers. */
    private inner class ClearingSignIn(private val rig: DiaryRig) :
        com.lumenpearson.lessons.core.data.repository.DiarySignIn by UnusedSignIn {
        private suspend fun registered(): Result<com.lumenpearson.lessons.core.data.repository.DiaryRegistration> {
            rig.cache.clear()
            val session = rig.store.currentDiarySession()!!
            return Result.success(com.lumenpearson.lessons.core.data.repository.DiaryRegistration(session, listOf(pupil(1))))
        }

        override suspend fun register(upstream: com.lumenpearson.lessons.core.data.upstream.UpstreamSession) = registered()

        override suspend fun signIn(target: DiaryTarget, password: String) = registered()
    }

    private fun pupil(id: Long) = DiaryStudent(
        id = id,
        firstName = "Pupil$id",
        lastName = "Ivanova",
        middleName = null,
        fullName = "Ivanova Pupil$id",
        school = null,
        className = null,
    )

    private fun com.lumenpearson.lessons.core.data.upstream.MutableClock.today() =
        DiaryWindows.today(java.time.ZoneId.of("Europe/Moscow"), this)

    private fun com.lumenpearson.lessons.core.data.upstream.MutableClock.monday() =
        DiaryWindows.weekStart(today())
}

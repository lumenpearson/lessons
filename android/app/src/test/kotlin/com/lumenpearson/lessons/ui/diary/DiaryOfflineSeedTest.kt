package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The diary drawn from what the phone saved: before the network answers,
 * instead of asking again while the save is fresh, and in place of a failure
 * card when the diary cannot be reached.
 *
 * This is what makes the diary a home a phone can open on a train. Before the
 * cache, every visit was a live read, so a family with no signal saw «Нет
 * связи» over a week they had looked at an hour earlier; and the import that
 * fills the phone would have been repeated, request for request, by the first
 * screen it landed on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryOfflineSeedTest {

    private val now: Instant = Instant.parse("2026-09-23T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val monday: LocalDate = LocalDate.of(2026, 9, 21)

    private val pupil = DiaryStudent(
        id = 1,
        firstName = "Пётр",
        lastName = "Иванов",
        middleName = null,
        fullName = "Иванов Пётр",
        school = null,
        className = null,
    )

    private fun lesson(subject: String) = DiaryLesson(
        date = monday,
        number = 1,
        subject = subject,
        startsAt = null,
        endsAt = null,
        room = null,
        teacher = null,
        homework = null,
        topic = null,
    )

    private val repository = FakeDiaryRepository().apply {
        students = listOf(pupil)
        sessions.value = DiarySession(
            login = "parent@example.com",
            token = "t",
            target = DiaryTarget.petersburg("parent@example.com"),
        )
    }
    private val cache = FakeDiaryCache()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model() = DiaryViewModel(repository, cache = cache, clock = clock)

    private fun subjects(model: DiaryViewModel) =
        model.uiState.value.days.flatMap { day -> day.lessons.map { it.subject } }

    @Test
    fun `the home draws what was saved before the network answers`() {
        cache.saveStudents(listOf(pupil), at = now.minus(Duration.ofHours(3)))
        cache.saveWeek(pupil.id, monday, listOf(lesson("Алгебра")), at = now.minus(Duration.ofHours(3)))
        repository.lessons = listOf(lesson("Физика"))
        val gate = CompletableDeferred<Unit>()
        repository.scheduleGate = gate

        val model = model()

        assertEquals(listOf("Алгебра"), subjects(model))
        assertEquals(false, model.uiState.value.scheduleLoading)

        gate.complete(Unit)
        assertEquals(listOf("Физика"), subjects(model))
    }

    @Test
    fun `a save younger than half an hour is not fetched again`() {
        cache.saveStudents(listOf(pupil), at = now.minus(Duration.ofMinutes(5)))
        cache.saveWeek(pupil.id, monday, listOf(lesson("Алгебра")), at = now.minus(Duration.ofMinutes(5)))

        val model = model()

        assertEquals(listOf("Алгебра"), subjects(model))
        assertEquals("the saved week was fresh", 0, repository.reloads)
        assertEquals("so were the saved pupils", 0, repository.studentReads)
    }

    @Test
    fun `offline with a save keeps the rows and says when they were saved`() {
        val savedAt = now.minus(Duration.ofHours(2))
        cache.saveStudents(listOf(pupil), at = now.minus(Duration.ofMinutes(1)))
        cache.saveWeek(pupil.id, monday, listOf(lesson("Алгебра")), at = savedAt)
        repository.scheduleFailure = DiaryFailure.Offline(IOException("no route"))

        val model = model()

        assertEquals(1, repository.reloads)
        assertEquals(listOf("Алгебра"), subjects(model))
        assertNull(model.uiState.value.scheduleError)
        assertEquals(savedAt, model.uiState.value.savedAt)
    }

    @Test
    fun `offline with nothing saved shows the failure card`() {
        repository.scheduleFailure = DiaryFailure.Offline(IOException("no route"))

        val model = model()

        assertTrue(model.uiState.value.scheduleError is DiaryFailure.Offline)
        assertNull(model.uiState.value.savedAt)
    }

    /**
     * Not every failure may hide behind the save: a diary switched off on the
     * server will not come back by waiting, and saying «нет связи» over old
     * rows would be the wrong sentence about the wrong problem.
     */
    @Test
    fun `a switched-off diary is said even over a save`() {
        cache.saveStudents(listOf(pupil), at = now)
        cache.saveWeek(pupil.id, monday, listOf(lesson("Алгебра")), at = now.minus(Duration.ofHours(2)))
        repository.scheduleFailure = DiaryFailure.Disabled

        val model = model()

        assertEquals(DiaryFailure.Disabled, model.uiState.value.scheduleError)
    }

    /**
     * A correction is applied by the server, so the read that follows a save
     * has to reach it — a fresh save holds the rows from before the correction.
     */
    @Test
    fun `a refresh goes to the diary even when the save is fresh`() {
        cache.saveStudents(listOf(pupil), at = now)
        cache.saveWeek(pupil.id, monday, listOf(lesson("Алгебра")), at = now)
        val model = model()
        assertEquals(0, repository.reloads)

        model.refresh()

        assertEquals(1, repository.reloads)
    }

    @Test
    fun `the pupil shown is the one chosen before`() {
        val sister = pupil.copy(id = 2, firstName = "Анна", fullName = "Иванова Анна")
        repository.students = listOf(pupil, sister)
        cache.saveStudents(listOf(pupil, sister), at = now)
        cache.selected.value = sister.id

        val model = model()

        assertEquals(sister.id, model.uiState.value.selectedStudentId)
    }

    @Test
    fun `a child picked on screen is kept for the next launch`() {
        val sister = pupil.copy(id = 2, firstName = "Анна", fullName = "Иванова Анна")
        repository.students = listOf(pupil, sister)
        val model = model()

        model.selectStudent(sister.id)

        assertEquals(sister.id, cache.selected.value)
    }

    /**
     * The home's start refresh and the screen's own load would otherwise both
     * read this week on every cold start. The load waits for the refresh and
     * finds the week it saved fresh.
     */
    @Test
    fun `the start refresh and the screen do not both read the week`() {
        // A dispatcher that runs nothing until told: on a phone the screen's
        // load suspends on the database before it reaches the network, and the
        // start effect registers the refresh in that gap.
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        cache.saveStudents(listOf(pupil), at = now)
        cache.saveWeek(pupil.id, monday, listOf(lesson("Алгебра")), at = now.minus(Duration.ofHours(2)))
        val refresh = CompletableDeferred<Result<Boolean>>()
        val model = model()
        model.refreshOnStart { refresh.await() }
        main.scheduler.runCurrent()
        assertEquals("the load waits for the refresh", 0, repository.reloads)

        // What `refreshIfStale` does: read the week, which writes it through.
        cache.saveWeek(pupil.id, monday, listOf(lesson("Физика")), at = now)
        refresh.complete(Result.success(true))
        main.scheduler.advanceUntilIdle()

        assertEquals("nothing read the week a second time", 0, repository.reloads)
        assertEquals(listOf("Физика"), subjects(model))
    }

    @Test
    fun `the week is the session's week, in the session's zone`() {
        // 20:00 UTC on Sunday is already Monday in Tomsk (UTC+7).
        val sundayEvening = Clock.fixed(Instant.parse("2026-09-27T20:00:00Z"), ZoneOffset.UTC)
        repository.sessions.value = DiarySession(
            login = "pupil",
            token = "t",
            target = DiaryTarget.netschool("tomsk", 1, null, "pupil", "Asia/Tomsk"),
        )

        val model = DiaryViewModel(repository, cache = cache, clock = sundayEvening)

        assertEquals(ZoneId.of("Asia/Tomsk"), model.uiState.value.zone)
        assertEquals(LocalDate.of(2026, 9, 28), model.uiState.value.weekStart)
        assertNotNull(model.uiState.value.session)
    }
}

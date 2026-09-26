package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A diary view model left over from an earlier home, while a later first run
 * signs in and imports.
 *
 * The view model is the activity's, so it outlives the diary home it was made
 * for — «Выйти из дневника» sends the phone back to the first run and leaves
 * it collecting. It took the first run's new session for its own: read the
 * pupils, and wrote the first of two as the stored choice, which is the key the
 * import asks before it offers a family its chooser. So a two-child family was
 * never asked, or was asked and then opened the home on the child it had not
 * picked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryOnboardingHoldTest {

    private val now: Instant = Instant.parse("2026-09-23T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun pupil(id: Long, name: String) = DiaryStudent(
        id = id,
        firstName = name,
        lastName = "Иванов",
        middleName = null,
        fullName = "Иванов $name",
        school = null,
        className = null,
    )

    private val first = pupil(1, "Пётр")
    private val second = pupil(2, "Анна")

    private val repository = FakeDiaryRepository().apply { students = listOf(first, second) }
    private val cache = FakeDiaryCache()
    private val held = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val session = DiarySession(
        login = "parent@example.com",
        token = "t",
        target = DiaryTarget.petersburg("parent@example.com"),
    )

    @Test
    fun `a view model from an earlier home leaves the first run's pupil to the first run`() {
        // The earlier home: signed out, and the phone back at the first run.
        val model = DiaryViewModel(repository, cache = cache, clock = clock, held = held)
        held.value = true

        // The first run's registration and its seed land.
        cache.saveStudents(listOf(first, second), at = now)
        repository.sessions.value = session

        assertNull("the view model chose for the import's chooser", cache.selected.value)
        assertEquals("the view model read the diary alongside the import", 0, repository.studentReads)

        // The family picks the second child, and the first run lets go.
        cache.selected.value = second.id
        held.value = false

        assertEquals(second.id, model.uiState.value.selectedStudentId)
        assertEquals(second.id, cache.selected.value)
    }

    @Test
    fun `a load an earlier session started does not land inside the hold`() {
        repository.sessions.value = session
        val model = DiaryViewModel(repository, cache = cache, clock = clock, held = held)
        assertEquals(listOf(first, second), model.uiState.value.students)

        held.value = true

        assertTrue(model.uiState.value.students.isEmpty())
        assertNull(model.uiState.value.selectedStudentId)
    }
}

package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.ui.onboarding.FakeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagementOwnershipTest {
    private val repository = FakeManageRepository()
    private val session = FakeSessionRepository()
    private val settings = FakeSettings()

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `a late A read cannot replace the selected B subjects`() {
        val model = model()
        model.loadSubjects()
        session.rejoin(2, "B")
        model.loadSubjects()
        val current = listOf(ManagedSubject(2, "B subject", null, null, null))
        repository.answer(1, Result.success(current))
        repository.answer(0, Result.success(listOf(ManagedSubject(1, "A subject", null, null, null))))
        assertEquals(current, model.uiState.value.subjects.value)
    }

    @Test
    fun `a late A role refusal cannot take the selected B page away`() {
        val model = model()
        model.loadSubjects()
        session.rejoin(2, "B")
        repository.answer(0, Result.failure(ManageFailure.RoleLost("admin")))
        assertNull(model.uiState.value.gone)
        assertNull(model.uiState.value.subjects.value)
    }

    @Test
    fun `an old write cannot clear the new class working flag or publish its notice`() {
        val model = model()
        model.setJoinMode(ClassJoinMode.INVITE)
        session.rejoin(2, "B")
        model.setJoinMode(ClassJoinMode.OPEN)
        repository.answer(0, Result.success(FakeSessionRepository.CLASS_CARD))
        assertTrue(model.uiState.value.working)
        assertNull(model.uiState.value.notice)
        assertNull(model.uiState.value.classCard.value)
        repository.answer(1, Result.success(FakeSessionRepository.CLASS_CARD.copy(name = "B")))
        assertEquals("B", model.uiState.value.classCard.value?.name)
    }

    @Test
    fun `replacement credentials for the same class invalidate an old read`() {
        val model = model()
        model.loadSubjects()
        session.rejoin(1, "A replacement")
        repository.answer(0, Result.success(listOf(ManagedSubject(1, "Old token", null, null, null))))
        assertNull(model.uiState.value.subjects.value)
    }

    @Test
    fun `a queued old write is not invoked after reset`() = runTest {
        val plumbing = ManagementPlumbing(backgroundScope)
        var calls = 0
        plumbing.write { calls++; Result.success(Unit) }
        reset(plumbing)
        runCurrent()
        assertEquals(0, calls)
    }

    @Test
    fun `a queued old read is not invoked after reset`() = runTest {
        val plumbing = ManagementPlumbing(backgroundScope)
        var calls = 0
        plumbing.read({ it }) { calls++ }
        reset(plumbing)
        runCurrent()
        assertEquals(0, calls)
    }

    @Test
    fun `changing the server address invalidates an in flight management read`() = runTest {
        val model = model()
        model.loadSubjects()
        settings.update { it.copy(baseUrl = "https://other.example/") }
        repository.answer(0, Result.success(listOf(ManagedSubject(1, "Previous server", null, null, null))))
        assertNull(model.uiState.value.subjects.value)
    }

    private fun model() = ManagementViewModel(repository, FakeDeviceLinkRepository(ClassRole.ADMIN), session, settings)

    private fun reset(plumbing: ManagementPlumbing) { plumbing.reset() }
}

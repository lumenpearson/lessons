package com.lumenpearson.lessons.ui.onboarding

import com.lumenpearson.lessons.core.data.catalog.DirectoryProblem
import com.lumenpearson.lessons.core.data.catalog.RegionLookupResult
import com.lumenpearson.lessons.core.data.catalog.SchoolDirectory
import com.lumenpearson.lessons.core.data.catalog.SchoolLookup
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step holders on their own: the sign-in's held session, the import's
 * rows and chooser, and the region search's two speeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStepsTest {

    private val target = DiaryTarget.netschool("samara", 239L, "Lyceum 239", login = "", zone = "Europe/Samara")
    private val password = "correct horse battery staple"

    // --- Sign-in ---------------------------------------------------------------------------

    @Test
    fun `the password is never a field of the sign-in step`() = runTest {
        val signIn = FakeSignIn().apply { registerResults += Result.failure(DiarySignInProblem.RegisterUnreachable) }
        val step = SignInStep(this, signIn)
        step.setLogin("ivan")
        step.submit(target, password) {}
        advanceUntilIdle()
        assertTrue("the session is held for the retry", step.state.value.sessionHeld)

        val kept = SignInStep::class.java.declaredFields.map { field ->
            field.isAccessible = true
            field.get(step)
        }
        for (value in kept + step.state.value) {
            assertFalse("$value", password in value.toString())
        }
    }

    @Test
    fun `a failure that discarded the session offers no retry`() = runTest {
        val signIn = FakeSignIn().apply {
            registerResults += Result.failure(DiarySignInProblem.ServerRefusedSession)
        }
        val step = SignInStep(this, signIn)
        step.setLogin("ivan")
        var registered = false
        step.submit(target, password) { registered = true }
        advanceUntilIdle()

        assertFalse(registered)
        assertFalse(step.state.value.sessionHeld)
        assertEquals(DiarySignInProblem.ServerRefusedSession, step.state.value.problem)
        step.retry { registered = true }
        advanceUntilIdle()
        assertEquals("no second registration without a session", 1, signIn.registered.size)
    }

    @Test
    fun `a new password discards the session still held`() = runTest {
        val signIn = FakeSignIn().apply { registerResults += Result.failure(DiarySignInProblem.RegisterUnreachable) }
        val step = SignInStep(this, signIn)
        step.setLogin("ivan")
        step.submit(target, password) {}
        advanceUntilIdle()
        val first = signIn.registered.single()

        step.submit(target, "another") {}
        advanceUntilIdle()

        assertEquals(listOf(first), signIn.discarded)
        assertEquals(2, signIn.opened.size)
        assertEquals("ivan", signIn.opened.last().first.login)
    }

    @Test
    fun `a failed preflight sends nothing to the diary`() = runTest {
        val signIn = FakeSignIn().apply { preflightResult = Result.failure(DiarySignInProblem.ServerMissing) }
        val step = SignInStep(this, signIn)
        step.submit(target, password) {}
        advanceUntilIdle()

        assertTrue(signIn.opened.isEmpty())
        assertEquals(DiarySignInProblem.ServerMissing, step.state.value.problem)
        assertTrue(step.state.value.dialog)
        step.edited()
        assertNull(step.state.value.problem)
    }

    // --- Import ----------------------------------------------------------------------------

    @Test
    fun `stage rows follow the phase under way`() {
        val running = ImportUi(phase = DiaryImportPhase.SCHEDULE)
        assertEquals(StageState.DONE, stageStateOf(running, DiaryImportPhase.STUDENTS))
        assertEquals(StageState.DONE, stageStateOf(running, DiaryImportPhase.PERIODS))
        assertEquals(StageState.RUNNING, stageStateOf(running, DiaryImportPhase.SCHEDULE))
        assertEquals(StageState.WAITING, stageStateOf(running, DiaryImportPhase.MARKS))

        val failed = ImportUi(failed = ImportFailure(DiaryImportPhase.HOMEWORK, DiarySignInProblem.Offline, true))
        assertEquals(StageState.DONE, stageStateOf(failed, DiaryImportPhase.SCHEDULE))
        assertEquals(StageState.FAILED, stageStateOf(failed, DiaryImportPhase.HOMEWORK))
        assertEquals(StageState.WAITING, stageStateOf(failed, DiaryImportPhase.MARKS))

        val done = ImportUi(done = ImportDone(1, 1, 1, setOf(DiaryImportPhase.MARKS)))
        assertEquals(StageState.DONE, stageStateOf(done, DiaryImportPhase.HOMEWORK))
        assertEquals(StageState.SKIPPED, stageStateOf(done, DiaryImportPhase.MARKS))
    }

    @Test
    fun `the bar never goes backwards`() = runTest {
        val import = FakeImport(
            steps = listOf(
                DiaryImportProgress.Running(DiaryImportPhase.SCHEDULE, 0.5f, 0.8f),
                DiaryImportProgress.Running(DiaryImportPhase.HOMEWORK, 0.3f, 0.9f),
                DiaryImportProgress.Failed(DiaryImportPhase.HOMEWORK, DiarySignInProblem.Offline, true, 0.4f),
            ),
        )
        val runner = ImportRunner(this, import)
        runner.start()
        advanceUntilIdle()

        assertEquals(0.5f, runner.state.value.completed)
        assertEquals(DiaryImportPhase.HOMEWORK, runner.state.value.failed?.phase)
    }

    @Test
    fun `several pupils pause the import until one is picked`() = runTest {
        val sister = pupil.copy(id = 8, firstName = "Anna", fullName = "Petrova Anna")
        val import = FakeImport(choosing = listOf(pupil, sister))
        val runner = ImportRunner(this, import)
        runner.start()
        runCurrent()

        assertEquals(listOf(7L, 8L), runner.state.value.choosing?.map { it.id })
        assertNull(runner.state.value.done)

        runner.choose(8)
        advanceUntilIdle()
        assertNull(runner.state.value.choosing)
        assertEquals(1f, runner.state.value.completed)
    }

    // --- Region search ---------------------------------------------------------------------

    @Test
    fun `typing searches the catalog at once and the directory only after a pause`() = runTest {
        val asked = mutableListOf<String>()
        val finder = RegionFinder(
            scope = this,
            catalog = { realCatalog },
            byName = { realSearch.search(it) },
            find = { query ->
                asked += query
                RegionLookupResult(query, realSearch.search(query), SchoolLookup.Generic)
            },
        )

        finder.type("Sa")
        runCurrent()
        assertTrue(finder.state.value.rows.isNotEmpty())
        advanceUntilIdle()
        assertTrue("under the directory's minimum nothing is asked", asked.isEmpty())

        finder.type("Sam")
        finder.type("Sama")
        finder.type("Samar")
        runCurrent()
        assertTrue(asked.isEmpty())
        advanceTimeBy(SchoolDirectory.DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals("one request for one pause in the typing", listOf("Samar"), asked)
        assertEquals(SchoolAnswer.Generic, finder.state.value.school)

        finder.submit()
        runCurrent()
        assertEquals(listOf("Samar", "Samar"), asked)
    }

    @Test
    fun `every directory failure says pick the region, and waiting is counted in minutes`() {
        fun failed(problem: DirectoryProblem) = answerOf(SchoolLookup.Failed(problem)) as SchoolAnswer.Failed
        assertEquals(SchoolAnswer.Failed(DirectoryFailure.WAIT, 2), failed(DirectoryProblem.Throttled(61)))
        assertEquals(SchoolAnswer.Failed(DirectoryFailure.WAIT, 1), failed(DirectoryProblem.Spent(1)))
        assertEquals(SchoolAnswer.Failed(DirectoryFailure.WAIT, null), failed(DirectoryProblem.Throttled(null)))
        assertEquals(DirectoryFailure.NO_SERVER, failed(DirectoryProblem.ServerMissing).reason)
        assertEquals(DirectoryFailure.OFFLINE, failed(DirectoryProblem.Offline).reason)
        assertEquals(DirectoryFailure.OFF, failed(DirectoryProblem.Disabled).reason)
        assertEquals(DirectoryFailure.OFF, failed(DirectoryProblem.ServerTooOld).reason)
    }
}

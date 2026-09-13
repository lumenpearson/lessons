package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
 * Whether the sign-in says what happened.
 *
 * It did not. A wrong password put a line of red text inside the form, below
 * two fields and above a button — which on a phone with the keyboard up is off
 * the bottom of the screen — and a correct one said nothing at all: the form
 * simply went away, which is also what leaving the screen looks like. «Даже не
 * понятно, ввёл я правильный пароль или нет» was the report, and it was right:
 * the state holder knew, and nothing it produced could be seen.
 *
 * So the outcome is now a value of its own, separate from the form's error, and
 * these are the tests that it is produced for both endings and cleared once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiarySignInOutcomeTest {

    private val repository = FakeDiaryRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model() = DiaryViewModel(repository)

    @Test
    fun `a refused password is an outcome that names the reason`() {
        repository.signInResult = Result.failure(DiaryFailure.SignInRequired)
        val model = model()

        model.signIn("parent@example.com", "wrong")

        val outcome = model.uiState.value.signInOutcome
        assertTrue("expected a failure, got $outcome", outcome is DiarySignInOutcome.Failed)
        assertEquals(
            DiaryFailure.SignInRequired,
            (outcome as DiarySignInOutcome.Failed).failure,
        )
        // And the form still knows to paint itself red, which is the half that
        // survives dismissing the pop-up.
        assertEquals(DiaryFailure.SignInRequired, model.uiState.value.signInError)
    }

    @Test
    fun `a diary that is down is not reported as a wrong password`() {
        // The whole point of carrying the failure rather than a boolean. These
        // two need different things from the person reading: one is a typo, the
        // other is waiting.
        repository.signInResult = Result.failure(DiaryFailure.Unavailable)
        val model = model()

        model.signIn("parent@example.com", "correct-horse")

        assertEquals(
            DiarySignInOutcome.Failed(DiaryFailure.Unavailable),
            model.uiState.value.signInOutcome,
        )
    }

    @Test
    fun `a correct password is an outcome too, with the login echoed back`() {
        // The silent case, and the one the report was actually about. The login
        // comes back with it because the commonest wrong answer to "did I type
        // it right" is an address that was accepted and is not yours.
        repository.signInResult = Result.success(DiarySession("parent@example.com", "token"))
        val model = model()

        model.signIn("parent@example.com", "correct-horse")

        assertEquals(
            DiarySignInOutcome.Succeeded("parent@example.com"),
            model.uiState.value.signInOutcome,
        )
        assertNull(model.uiState.value.signInError)
    }

    @Test
    fun `the outcome is shown once and then gone`() {
        repository.signInResult = Result.failure(DiaryFailure.SignInRequired)
        val model = model()
        model.signIn("parent@example.com", "wrong")

        model.consumeSignInOutcome()

        assertNull(model.uiState.value.signInOutcome)
        // Dismissing the pop-up is not the same as fixing the form: the fields
        // stay red until something is typed into them.
        assertEquals(DiaryFailure.SignInRequired, model.uiState.value.signInError)
    }

    @Test
    fun `a second attempt replaces the first outcome rather than queueing behind it`() {
        repository.signInResult = Result.failure(DiaryFailure.Unavailable)
        val model = model()
        model.signIn("parent@example.com", "correct-horse")

        repository.signInResult = Result.failure(DiaryFailure.SignInRequired)
        model.signIn("parent@example.com", "wrong")

        assertEquals(
            DiarySignInOutcome.Failed(DiaryFailure.SignInRequired),
            model.uiState.value.signInOutcome,
        )
    }
}

/**
 * A diary that answers `signIn` however the test says and nothing else.
 *
 * The reads all fail, on purpose: the tests here are about the sign-in and
 * nothing after it, and a students call that succeeded would pull the state
 * holder into a load whose answers would have to be arranged too.
 */
private class FakeDiaryRepository : DiaryRepository {

    var signInResult: Result<DiarySession> = Result.failure(DiaryFailure.Unavailable)

    private val sessions = MutableStateFlow<DiarySession?>(null)
    override val session: Flow<DiarySession?> = sessions

    override suspend fun current(): DiarySession? = sessions.value

    override suspend fun signIn(login: String, password: String): Result<DiarySession> =
        signInResult.also { result -> result.getOrNull()?.let { sessions.value = it } }

    override suspend fun signOut(): Result<Unit> {
        sessions.value = null
        return Result.success(Unit)
    }

    override suspend fun students(): Result<List<DiaryStudent>> =
        Result.failure(DiaryFailure.Unavailable)

    override suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryLesson>> = Result.failure(DiaryFailure.Unavailable)

    override suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryHomework>> = Result.failure(DiaryFailure.Unavailable)

    override suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryMark>> = Result.failure(DiaryFailure.Unavailable)

    override suspend fun periods(studentId: Long): Result<List<DiaryPeriod>> =
        Result.failure(DiaryFailure.Unavailable)
}

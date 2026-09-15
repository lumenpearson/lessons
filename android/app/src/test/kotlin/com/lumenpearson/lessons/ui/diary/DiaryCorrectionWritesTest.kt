package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.repository.DiaryEdit
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryOverrideRecord
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a save actually writes.
 *
 * This is the decision the whole feature turns on and it is not visible
 * anywhere: the sheet is prefilled with what is **shown**, which for a
 * corrected field is the correction, while the comparison that decides between
 * writing one and taking one off is against what the **diary** says. Get it
 * backwards and typing a field back to the school's own answer stores a
 * correction saying "show exactly what you were going to show anyway" — a row
 * that marks the lesson as corrected forever, with a reset button that appears
 * to do nothing.
 *
 * The rest of these are the paths a person actually meets: a half-written save,
 * a session that died between opening the sheet and pressing Save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryCorrectionWritesTest {

    private val repository = RecordingDiaryRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private fun lesson(
        room: String? = null,
        teacher: String? = null,
        edits: List<DiaryEdit> = emptyList(),
    ) = DiaryLesson(
        date = monday,
        number = 1,
        subject = "Алгебра",
        startsAt = null,
        endsAt = null,
        room = room,
        teacher = teacher,
        homework = null,
        topic = null,
        target = TARGET,
        edits = edits,
    )

    /** A view model with a pupil chosen and one row open in the sheet. */
    private fun opened(lesson: DiaryLesson): DiaryViewModel {
        repository.students = listOf(
            DiaryStudent(
                id = 1,
                firstName = "Пётр",
                lastName = "Иванов",
                middleName = null,
                fullName = "Иванов Пётр",
                school = null,
                className = null,
            ),
        )
        val model = DiaryViewModel(repository)
        // Through the view model, so the session collector in `init` sees it
        // and loads the pupil list the way it would on a real sign-in.
        model.signIn("parent@example.com", "correct")
        model.selectStudent(1)
        model.edit(lesson.corrections())
        return model
    }

    // -- the decision -------------------------------------------------------

    @Test
    fun `a field typed to something new is written as a correction over the diary`() {
        val model = opened(lesson(room = "12"))

        model.saveEdit(mapOf(DiaryField.ROOM to "204"))

        assertEquals(
            listOf(Written(TARGET, DiaryField.ROOM, "204", original = "12")),
            repository.corrections,
        )
        assertTrue(repository.resets.isEmpty())
    }

    @Test
    fun `a corrected field typed back to what the diary says is a reset`() {
        // The field is prefilled with "204" — the correction — and the diary
        // says "12". Typing "12" means "never mind", and storing a correction
        // equal to the upstream would leave the row marked corrected for good.
        val model = opened(
            lesson(
                room = "204",
                edits = listOf(DiaryEdit("room", "204", original = "12", changedUpstream = false)),
            ),
        )

        model.saveEdit(mapOf(DiaryField.ROOM to "12"))

        assertEquals(listOf(TARGET to DiaryField.ROOM), repository.resets)
        assertTrue(repository.corrections.isEmpty())
    }

    @Test
    fun `a field nobody touched costs no request`() {
        // The sheet hands back every field it drew, so this is the ordinary
        // case: one room changed, three fields returned unchanged.
        val model = opened(lesson(room = "12", teacher = "Иванова"))

        model.saveEdit(
            mapOf(
                DiaryField.ROOM to "204",
                DiaryField.TEACHER to "Иванова",
                DiaryField.TOPIC to "",
            ),
        )

        assertEquals(1, repository.corrections.size)
        assertTrue(repository.resets.isEmpty())
    }

    @Test
    fun `an untouched blank field is not mistaken for a reset`() {
        val model = opened(lesson())

        model.saveEdit(mapOf(DiaryField.TOPIC to ""))

        assertTrue(repository.corrections.isEmpty())
        assertTrue(repository.resets.isEmpty())
    }

    @Test
    fun `re-saving an unchanged correction costs nothing`() {
        val model = opened(
            lesson(
                room = "204",
                edits = listOf(DiaryEdit("room", "204", original = "12", changedUpstream = false)),
            ),
        )

        model.saveEdit(mapOf(DiaryField.ROOM to "204"))

        assertTrue(repository.corrections.isEmpty())
        assertTrue(repository.resets.isEmpty())
    }

    @Test
    fun `re-saving a correction the diary has moved under is written again`() {
        // «В дневнике теперь другое» has to be acknowledgeable. Skipping the
        // write leaves `original` stale, so the warning comes back every time
        // the sheet is opened and there is no way to say "yes, keep mine".
        val model = opened(
            lesson(
                room = "204",
                edits = listOf(DiaryEdit("room", "204", original = "301", changedUpstream = true)),
            ),
        )

        model.saveEdit(mapOf(DiaryField.ROOM to "204"))

        assertEquals(
            listOf(Written(TARGET, DiaryField.ROOM, "204", original = "301")),
            repository.corrections,
        )
    }

    @Test
    fun `whitespace is not a change`() {
        val model = opened(lesson(room = "12"))

        model.saveEdit(mapOf(DiaryField.ROOM to "  12  "))

        assertTrue(repository.corrections.isEmpty())
        assertTrue(repository.resets.isEmpty())
    }

    // -- resetting ----------------------------------------------------------

    @Test
    fun `a reset takes off the corrections, not the fields the sheet drew`() {
        // The two sets are not the same: the sheet draws room, teacher and
        // topic, and only the room is corrected. Resetting the drawn ones sends
        // two deletes that do nothing and leaves the row still marked.
        val model = opened(
            lesson(
                room = "204",
                edits = listOf(DiaryEdit("room", "204", original = "12", changedUpstream = false)),
            ),
        )

        model.resetEdit()

        assertEquals(listOf(TARGET to DiaryField.ROOM), repository.resets)
    }

    // -- when it goes wrong -------------------------------------------------

    @Test
    fun `a save that fails halfway leaves the sheet open and says why`() {
        val model = opened(lesson(room = "12", teacher = "Иванова"))
        repository.failAfter = 1

        model.saveEdit(
            mapOf(DiaryField.ROOM to "204", DiaryField.TEACHER to "Петрова"),
        )

        val state = model.uiState.value
        assertNotNull("the sheet stays open with what was typed", state.editing)
        assertEquals(DiaryFailure.Unavailable, state.editError)
        // One of the two was written, so the screen behind is now wrong about
        // this row until it is re-read.
        assertEquals(1, repository.corrections.size)
        assertTrue(repository.reloads > 0)
    }

    @Test
    fun `a save that meets a dead upstream session closes the sheet for the prompt`() {
        val model = opened(lesson(room = "12"))
        repository.failAfter = 0
        repository.failure = DiaryFailure.ReauthRequired

        model.saveEdit(mapOf(DiaryField.ROOM to "204"))

        val state = model.uiState.value
        // Otherwise a modal sheet with no message in it sits over the password
        // form that was just raised underneath it.
        assertNull(state.editing)
        assertTrue(state.reauth)
        assertNull(state.editError)
    }

    private companion object {
        const val TARGET = "lesson:2026-09-07:n1:Алгебра"
    }

    private data class Written(
        val target: String,
        val field: DiaryField,
        val value: String,
        val original: String?,
    )

    /** Records what was asked of it, and can be told to stop answering. */
    private class RecordingDiaryRepository : DiaryRepository {

        var students: List<DiaryStudent> = emptyList()
        var failAfter: Int? = null
        var failure: DiaryFailure = DiaryFailure.Unavailable
        var reloads: Int = 0

        val corrections = mutableListOf<Written>()
        val resets = mutableListOf<Pair<String, DiaryField>>()

        private val sessions = MutableStateFlow<DiarySession?>(null)
        override val session: Flow<DiarySession?> = sessions

        private var writes = 0

        private fun <T> answer(value: T): Result<T> {
            val limit = failAfter
            val outcome = if (limit != null && writes >= limit) {
                Result.failure<T>(failure)
            } else {
                Result.success(value)
            }
            writes += 1
            return outcome
        }

        override suspend fun current(): DiarySession? = sessions.value

        override suspend fun signIn(login: String, password: String): Result<DiarySession> {
            val opened = DiarySession(login = login, token = "t")
            sessions.value = opened
            return Result.success(opened)
        }

        override suspend fun signOut(): Result<Unit> {
            sessions.value = null
            return Result.success(Unit)
        }

        override suspend fun students(): Result<List<DiaryStudent>> = Result.success(students)

        override suspend fun schedule(
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
        ): Result<List<DiaryLesson>> {
            reloads += 1
            return Result.success(emptyList())
        }

        override suspend fun homework(
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
        ): Result<List<DiaryHomework>> = Result.success(emptyList())

        override suspend fun grades(
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
        ): Result<List<DiaryMark>> = Result.success(emptyList())

        override suspend fun periods(studentId: Long): Result<List<DiaryPeriod>> =
            Result.success(emptyList())

        override suspend fun overrides(studentId: Long): Result<List<DiaryOverrideRecord>> =
            Result.success(emptyList())

        override suspend fun correct(
            studentId: Long,
            target: String,
            field: DiaryField,
            value: String,
            original: String?,
        ): Result<Unit> {
            val outcome = answer(Unit)
            if (outcome.isSuccess) corrections += Written(target, field, value, original)
            return outcome
        }

        override suspend fun reset(
            studentId: Long,
            target: String,
            field: DiaryField,
        ): Result<Unit> {
            val outcome = answer(Unit)
            if (outcome.isSuccess) resets += target to field
            return outcome
        }

        override suspend fun resetAll(studentId: Long): Result<Unit> = answer(Unit)
    }
}

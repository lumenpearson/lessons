package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.DiaryApi
import com.lumenpearson.lessons.core.data.network.dto.DiaryAttendanceDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLoginRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLoginResponseDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryMarkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryStudentDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySubjectDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryTeacherDto
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * What the repository does with the stored session, which is the half of this
 * feature no screen can see and every screen depends on.
 *
 * The store is a fake rather than DataStore: what is under test is the rule —
 * a dead token is dropped, an expired *upstream* session is not, and a sign-out
 * is local whatever the network says — and none of that is about files.
 */
class DiaryRepositoryTest {

    private val store = FakeStore()

    @Test
    fun `signing in stores the token the server minted`() = runTest {
        val repository = repository(FakeApi())

        val result = repository.signIn(" parent@example.com ", "hunter2")

        assertEquals("diary-token", result.getOrNull()?.token)
        assertEquals("parent@example.com", store.stored?.login)
        assertEquals("diary-token", store.stored?.token)
    }

    /**
     * The password is the one value in this app that must not be kept, so the
     * test that would catch it being kept is the one that looks at the store.
     */
    @Test
    fun `signing in stores nothing but the login and the token`() = runTest {
        val repository = repository(FakeApi())

        repository.signIn("parent@example.com", "hunter2")

        val stored = store.stored
        assertTrue(stored != null && "hunter2" !in stored.toString())
    }

    @Test
    fun `refused credentials are a sign-in failure and store nothing`() = runTest {
        val repository = repository(FakeApi(loginFailure = httpError(401)))

        val result = repository.signIn("parent@example.com", "wrong")

        assertEquals(DiaryFailure.SignInRequired, result.exceptionOrNull())
        assertNull(store.stored)
    }

    @Test
    fun `a dead session token is dropped so the section can offer a sign-in`() = runTest {
        store.stored = DiarySession(login = "parent@example.com", token = "stale")
        val repository = repository(FakeApi(callFailure = httpError(401)))

        val result = repository.students()

        assertEquals(DiaryFailure.SignInRequired, result.exceptionOrNull())
        assertNull(store.stored)
    }

    /**
     * The opposite case, and the reason the header exists: our session is still
     * ours, so the login stays — it is what the password prompt is about to
     * say out loud.
     */
    @Test
    fun `an expired upstream session keeps the stored login`() = runTest {
        store.stored = DiarySession(login = "parent@example.com", token = "ours")
        val repository = repository(
            FakeApi(callFailure = httpError(401, DiaryFailure.REAUTH_HEADER to "required")),
        )

        val result = repository.students()

        assertEquals(DiaryFailure.ReauthRequired, result.exceptionOrNull())
        assertEquals("parent@example.com", store.stored?.login)
    }

    @Test
    fun `signing out clears the session even when the server cannot be told`() = runTest {
        store.stored = DiarySession(login = "parent@example.com", token = "ours")
        val repository = repository(FakeApi(logoutFailure = IOException("no network")))

        val result = repository.signOut()

        assertTrue(result.isSuccess)
        assertNull(store.stored)
    }

    /** The server's own 62-day rule, applied before the round trip rather than after it. */
    @Test
    fun `an over-wide range fails without asking the server`() = runTest {
        val api = FakeApi()
        val repository = repository(api)

        val result = repository.schedule(
            studentId = 1,
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 12, 31),
        )

        assertEquals(DiaryFailure.BadRange, result.exceptionOrNull())
        assertEquals(0, api.scheduleCalls)
    }

    @Test
    fun `an inverted range is refused too`() = runTest {
        val repository = repository(FakeApi())

        val result = repository.grades(
            studentId = 1,
            from = LocalDate.of(2026, 9, 10),
            to = LocalDate.of(2026, 9, 1),
        )

        assertEquals(DiaryFailure.BadRange, result.exceptionOrNull())
    }

    @Test
    fun `a week of lessons maps through to domain rows`() = runTest {
        val repository = repository(FakeApi())

        val lessons = repository.schedule(
            studentId = 1,
            from = LocalDate.of(2026, 9, 14),
            to = LocalDate.of(2026, 9, 20),
        ).getOrNull().orEmpty()

        assertEquals(1, lessons.size)
        assertEquals("Алгебра", lessons.first().subject)
    }

    /**
     * The 422 the corrections answer with is not the 422 the reads answer with,
     * and only the caller knows which it asked for. All three writes have to
     * pass that on, so all three are checked: one that forgot would report
     * "диапазон слишком широкий" over a correction nobody can file.
     */
    @Test
    fun `a server that refuses a correction is a rejection, on every write`() = runTest {
        val repository = repository(FakeApi(callFailure = httpError(422)))

        assertEquals(
            DiaryFailure.Rejected,
            repository.correct(1, TARGET, DiaryField.ROOM, "301", null).exceptionOrNull(),
        )
        assertEquals(
            DiaryFailure.Rejected,
            repository.reset(1, TARGET, DiaryField.ROOM).exceptionOrNull(),
        )
        assertEquals(DiaryFailure.Rejected, repository.resetAll(1).exceptionOrNull())
    }

    /** The other half of the same rule: a read's 422 still means the range. */
    @Test
    fun `a 422 on a read is still about the date range`() = runTest {
        val repository = repository(FakeApi(callFailure = httpError(422)))

        val result = repository.schedule(
            studentId = 1,
            from = LocalDate.of(2026, 9, 14),
            to = LocalDate.of(2026, 9, 20),
        )

        assertEquals(DiaryFailure.BadRange, result.exceptionOrNull())
    }

    /**
     * The key is the server's and is echoed, never rebuilt — including its
     * trailing space, which belongs to the subject name it was composed from.
     * A client that tidied it would file the correction under a lesson that
     * does not exist, and the reset button for it would never appear.
     */
    @Test
    fun `a correction echoes the target and names the field the server's way`() = runTest {
        val api = FakeApi()
        val repository = repository(api)

        val result = repository.correct(
            studentId = 1,
            target = TARGET,
            field = DiaryField.ROOM,
            value = "301",
            original = "204",
        )

        assertTrue(result.isSuccess)
        assertEquals(TARGET, api.written?.target)
        assertEquals("room", api.written?.field)
        assertEquals("301", api.written?.value)
        assertEquals("204", api.written?.original)
    }

    @Test
    fun `a reset names the same target and field`() = runTest {
        val api = FakeApi()
        val repository = repository(api)

        repository.reset(studentId = 1, target = TARGET, field = DiaryField.TEXT)

        assertEquals(TARGET to "text", api.reset)
    }

    /**
     * The decision written up in [DiaryRepositoryImpl]: a write that meets a
     * dead token signs the family out, exactly as a read does. The correction
     * was refused before it was stored, so keeping the token could not have
     * saved it — and keeping it would leave them on a screen that looks signed
     * in and fails everything.
     */
    @Test
    fun `a dead token on a correction signs the family out`() = runTest {
        store.stored = DiarySession(login = "parent@example.com", token = "stale")
        val repository = repository(FakeApi(callFailure = httpError(401)))

        val result = repository.correct(1, TARGET, DiaryField.ROOM, "301", null)

        assertEquals(DiaryFailure.SignInRequired, result.exceptionOrNull())
        assertNull(store.stored)
    }

    /**
     * A newer server may know a correctable field this build does not. Showing
     * the row would mean showing a reset button with no field to send, so it is
     * left out — and «сбросить всё» still reaches it.
     */
    @Test
    fun `a stored correction this build cannot name is left out of the list`() = runTest {
        val api = FakeApi(
            stored = listOf(
                DiaryOverrideDto(target = TARGET, field = "room", value = "301"),
                DiaryOverrideDto(target = TARGET, field = "canteen", value = "нет"),
            ),
        )
        val repository = repository(api)

        val rows = repository.overrides(1).getOrNull().orEmpty()

        assertEquals(1, rows.size)
        assertEquals(DiaryField.ROOM, rows.first().field)
        assertEquals(TARGET, rows.first().target)
    }

    private fun repository(api: DiaryApi) = DiaryRepositoryImpl(
        api = api,
        store = store,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /** The store, with nothing in it but what was last written. */
    private class FakeStore : DiarySessionStore {
        var stored: DiarySession? = null
            set(value) {
                field = value
                flow.value = value
            }

        private val flow = MutableStateFlow<DiarySession?>(null)
        override val diarySession: Flow<DiarySession?> get() = flow
        override suspend fun currentDiarySession(): DiarySession? = stored
        override suspend fun writeDiarySession(value: DiarySession) {
            stored = value
        }

        override suspend fun clearDiarySession() {
            stored = null
        }
    }

    /**
     * The server, as far as this test is concerned: one pupil, one lesson, and
     * whichever failure the case under test asked for.
     */
    private class FakeApi(
        private val loginFailure: Throwable? = null,
        private val logoutFailure: Throwable? = null,
        private val callFailure: Throwable? = null,
        private val stored: List<DiaryOverrideDto> = emptyList(),
    ) : DiaryApi {

        var scheduleCalls: Int = 0
            private set

        /**
         * What the last correction was sent as, recorded *before* any failure
         * is thrown: a write that the server refuses still has to have asked
         * for the right thing.
         */
        var written: DiaryOverrideRequestDto? = null
            private set

        var reset: Pair<String, String>? = null
            private set

        var resetAllCalls: Int = 0
            private set

        override suspend fun login(body: DiaryLoginRequestDto): DiaryLoginResponseDto {
            loginFailure?.let { throw it }
            return DiaryLoginResponseDto(token = "diary-token", login = body.login)
        }

        override suspend fun logout() {
            logoutFailure?.let { throw it }
        }

        override suspend fun students(): List<DiaryStudentDto> {
            callFailure?.let { throw it }
            return listOf(
                DiaryStudentDto(
                    id = 1,
                    firstName = "Иван",
                    lastName = "Иванов",
                    fullName = "Иванов Иван",
                ),
            )
        }

        override suspend fun schedule(
            studentId: Long,
            from: String,
            to: String,
        ): List<DiaryLessonDto> {
            scheduleCalls++
            callFailure?.let { throw it }
            return listOf(
                DiaryLessonDto(
                    date = "2026-09-14",
                    number = 1,
                    subject = "Алгебра",
                    startsAt = "08:30:00",
                    endsAt = "09:15:00",
                ),
            )
        }

        override suspend fun homework(
            studentId: Long,
            from: String,
            to: String,
        ): List<DiaryHomeworkDto> {
            callFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun grades(
            studentId: Long,
            from: String,
            to: String,
        ): List<DiaryMarkDto> {
            callFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun periods(studentId: Long): List<DiaryPeriodDto> {
            callFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun subjects(studentId: Long, periodId: Long?): List<DiarySubjectDto> {
            callFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun teachers(studentId: Long): List<DiaryTeacherDto> {
            callFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun attendance(studentId: Long): List<DiaryAttendanceDto> {
            callFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun overrides(studentId: Long): List<DiaryOverrideDto> {
            callFailure?.let { throw it }
            return stored
        }

        override suspend fun putOverride(
            studentId: Long,
            body: DiaryOverrideRequestDto,
        ): DiaryOverrideDto {
            written = body
            callFailure?.let { throw it }
            return DiaryOverrideDto(
                target = body.target,
                field = body.field,
                value = body.value,
                original = body.original,
                updatedAt = "2026-09-15T10:00:00+03:00",
            )
        }

        override suspend fun resetOverride(studentId: Long, target: String, field: String) {
            reset = target to field
            callFailure?.let { throw it }
        }

        override suspend fun resetOverrides(studentId: Long) {
            resetAllCalls++
            callFailure?.let { throw it }
        }
    }
}

/**
 * A key as the server composes it: the day, the lesson number when there is
 * one, and the subject — trailing space and all.
 */
private const val TARGET = "lesson:2026-09-14:n2:Алгебра "

private fun httpError(code: Int, vararg headers: Pair<String, String>): HttpException {
    val request = Request.Builder().url("https://school.example/api/v1/diary/students").build()
    val raw = okhttp3.Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("error")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    return HttpException(
        Response.error<Unit>(
            """{"detail":"no"}""".toResponseBody("application/json".toMediaType()),
            raw,
        ),
    )
}

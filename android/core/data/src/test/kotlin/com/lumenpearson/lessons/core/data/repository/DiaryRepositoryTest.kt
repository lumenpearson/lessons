package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.DiaryApi
import com.lumenpearson.lessons.core.data.network.dto.DiaryAttendanceDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLoginRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLoginResponseDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryMarkDto
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
    ) : DiaryApi {

        var scheduleCalls: Int = 0
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
    }
}

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

package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.DiaryApi
import com.lumenpearson.lessons.core.data.network.dto.DiaryAttendanceDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryCapabilitiesDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryMarkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryResetRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionResponseDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryStudentDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySubjectDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryTeacherDto
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
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

    /**
     * The repository's sign-in is [DiarySignIn]'s, whole: the target goes
     * through as it was given and the session that comes back is the one the
     * sign-in stored. What is stored, and what never is, is `DiarySignInTest`'s.
     */
    @Test
    fun `signing in hands the target to the sign-in and answers its session`() = runTest {
        val signIn = FakeSignIn()
        val repository = repository(FakeApi(), signIn)
        val target = DiaryTarget.netschool("samara", 1234, null, "ivanova", "Europe/Samara")

        val result = repository.signIn(target, "hunter2")

        assertEquals(listOf(target), signIn.asked)
        assertEquals("diary-token", result.getOrNull()?.token)
    }

    @Test
    fun `a refused sign-in comes back as the sign-in's own problem`() = runTest {
        val signIn = FakeSignIn(answer = Result.failure(DiarySignInProblem.WrongPassword("Неверный пароль")))
        val repository = repository(FakeApi(), signIn)

        val result = repository.signIn(DiaryTarget.petersburg("parent@example.com"), "wrong")

        assertEquals(DiarySignInProblem.WrongPassword("Неверный пароль"), result.exceptionOrNull())
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
     * The bearer is dead; the account is not. Without the target, a family
     * that had only ever used a «Сетевой город» diary would be sent back to
     * the start by an expired token instead of being asked for the password.
     */
    @Test
    fun `a dead session token keeps which diary it was for`() = runTest {
        val target = DiaryTarget.netschool("samara", 1234, "Школа № 5", "ivanova", "Europe/Samara")
        store.stored = DiarySession(login = "ivanova", token = "stale", target = target)
        val repository = repository(FakeApi(callFailure = httpError(401)))

        repository.students()

        assertNull(store.stored)
        assertEquals(target, store.target)
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
        store.student = 7
        var forgotten = 0
        val repository = DiaryRepositoryImpl(
            api = FakeApi(logoutFailure = IOException("no network")),
            store = store,
            diarySignIn = FakeSignIn(),
            forgetLocal = { forgotten++ },
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val result = repository.signOut()

        assertTrue(result.isSuccess)
        assertNull(store.stored)
        // Signing out is the one thing that forgets the account itself, and
        // whatever was kept offline for it.
        assertNull(store.target)
        assertNull(store.student)
        assertEquals(1, forgotten)
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

    /**
     * The other half of the same rule, and the contrast is what makes it a
     * test: one server, one 422, two meanings. Asserting only the read would
     * keep passing with the whole `unprocessable` argument deleted, since
     * whichever meaning was left as the default would be the one it checked.
     */
    @Test
    fun `the same 422 is the date range on a read and a refusal on a write`() = runTest {
        val repository = repository(FakeApi(callFailure = httpError(422)))

        val read = repository.schedule(
            studentId = 1,
            from = LocalDate.of(2026, 9, 14),
            to = LocalDate.of(2026, 9, 20),
        )
        val write = repository.reset(1, TARGET, DiaryField.ROOM)

        assertEquals(DiaryFailure.BadRange, read.exceptionOrNull())
        assertEquals(DiaryFailure.Rejected, write.exceptionOrNull())
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

        assertEquals(TARGET, api.reset?.target)
        assertEquals("text", api.reset?.field)
    }

    /**
     * Why the reset stopped being a query string. A key is composed from a
     * subject name, so it can hold an ampersand — which any URL parser reads as
     * the start of the next parameter — and a colon, which is the separator the
     * key itself is built from. Both have to arrive byte for byte, because the
     * server matches the whole string and answers 204 either way: a reset that
     * matched nothing would look exactly like one that worked.
     */
    @Test
    fun `a reset sends the target verbatim, ampersand and colon and all`() = runTest {
        val api = FakeApi()
        val repository = repository(api)

        val ampersand = "lesson:2026-09-14:n2:Физика & астрономия"
        repository.reset(studentId = 1, target = ampersand, field = DiaryField.ROOM)
        assertEquals(ampersand, api.reset?.target)

        val colon = "lesson:2026-09-14:n3:ОБЖ: основы безопасности"
        repository.reset(studentId = 1, target = colon, field = DiaryField.TOPIC)
        assertEquals(colon, api.reset?.target)
        assertEquals("topic", api.reset?.field)
    }

    /**
     * «Сбросить всё» is one call of its own and not a loop over the list. The
     * rows this build cannot name a field for are dropped from that list, so a
     * loop would leave behind precisely the corrections a family has no other
     * way to be rid of.
     */
    @Test
    fun `resetting everything is one call, not a reset for each row`() = runTest {
        val api = FakeApi(
            stored = listOf(
                DiaryOverrideDto(target = TARGET, field = "room", value = "301"),
                DiaryOverrideDto(target = TARGET, field = "canteen", value = "нет"),
            ),
        )
        val repository = repository(api)

        assertTrue(repository.resetAll(1).isSuccess)

        assertEquals(1, api.resetAllCalls)
        assertNull(api.reset)
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

    /**
     * The same 401, on a phone whose disk will not take the write.
     *
     * DataStore's write side rethrows [IOException], and dropping the dead
     * token happens inside the `catch` that is turning the refusal into a
     * `Result` — so a full disk turned a handled 401 into an exception thrown
     * out of a function whose whole signature promises it will not. Every
     * caller is a bare `viewModelScope.launch`, which means the process goes
     * down while the diary screen is open. `signOut` already guards its own
     * clear, and `TimetableRepositoryImpl` guards the class token's for exactly
     * this reason; this is the one path that did not.
     */
    @Test
    fun `a 401 whose sign-out cannot be written is still a failure, not a crash`() = runTest {
        val unwritable = object : DiarySessionStore by store {
            override suspend fun clearDiaryToken() = throw IOException("no space left on device")
        }
        val repository = DiaryRepositoryImpl(
            api = FakeApi(callFailure = httpError(401)),
            store = unwritable,
            diarySignIn = FakeSignIn(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val result = repository.students()

        assertEquals(DiaryFailure.SignInRequired, result.exceptionOrNull())
    }

    private fun repository(api: DiaryApi, signIn: DiarySignIn = FakeSignIn()) = DiaryRepositoryImpl(
        api = api,
        store = store,
        diarySignIn = signIn,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /** A sign-in that answers what it is told and records what it was asked for. */
    private class FakeSignIn(
        private val answer: Result<DiaryRegistration>? = null,
    ) : DiarySignIn {
        val asked = mutableListOf<DiaryTarget>()

        override suspend fun preflight(target: DiaryTarget): Result<Unit> = Result.success(Unit)

        override suspend fun openUpstream(target: DiaryTarget, password: String): Result<UpstreamSession> =
            Result.failure(DiarySignInProblem.Unexpected("not used here"))

        override suspend fun register(upstream: UpstreamSession): Result<DiaryRegistration> =
            Result.failure(DiarySignInProblem.Unexpected("not used here"))

        override suspend fun discard(upstream: UpstreamSession) = Unit

        override suspend fun signIn(target: DiaryTarget, password: String): Result<DiaryRegistration> {
            asked += target
            return answer ?: Result.success(
                DiaryRegistration(
                    session = DiarySession(login = target.login, token = "diary-token", target = target),
                    students = emptyList(),
                ),
            )
        }
    }

    /** The store, with nothing in it but what was last written. */
    private class FakeStore : DiarySessionStore {
        var stored: DiarySession? = null
            set(value) {
                field = value
                flow.value = value
                if (value != null) target = value.target
            }
        var target: DiaryTarget? = null
        var student: Long? = null

        private val flow = MutableStateFlow<DiarySession?>(null)
        override val diarySession: Flow<DiarySession?> get() = flow
        override suspend fun currentDiarySession(): DiarySession? = stored
        override suspend fun writeDiarySession(value: DiarySession) {
            stored = value
        }

        override suspend fun clearDiaryToken() {
            stored = null
        }

        override suspend fun forgetDiary() {
            stored = null
            target = null
            student = null
        }

        override val diaryTarget: Flow<DiaryTarget?> get() = MutableStateFlow(target)
        override suspend fun currentDiaryTarget(): DiaryTarget? = target
        override val selectedStudentId: Flow<Long?> get() = MutableStateFlow(student)
        override suspend fun selectStudent(id: Long?) {
            student = id
        }
    }

    /**
     * The server, as far as this test is concerned: one pupil, one lesson, and
     * whichever failure the case under test asked for.
     */
    private class FakeApi(
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

        /** The body of the last single-field reset, kept whole so a test can
         * look at the target that travelled rather than at a re-encoding of it. */
        var reset: DiaryResetRequestDto? = null
            private set

        var resetAllCalls: Int = 0
            private set

        override suspend fun capabilities(): DiaryCapabilitiesDto = DiaryCapabilitiesDto(enabled = true, registration = true)

        override suspend fun registerSession(body: DiarySessionRequestDto): DiarySessionResponseDto =
            throw AssertionError("the repository registers nothing itself; DiarySignIn does")

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
                originalWhenWritten = body.original,
                updatedAt = "2026-09-15T10:00:00+03:00",
            )
        }

        override suspend fun resetOverride(studentId: Long, body: DiaryResetRequestDto) {
            reset = body
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

package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.ManageApi
import com.lumenpearson.lessons.core.data.network.dto.ClassPatchDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedClassDto
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * What happens when the server stops accepting this device's token.
 *
 * The bug this pins is the one from the report: an owner deleted their class —
 * from the app or from the bot — and the app said so and then carried on. It
 * kept the session and the whole cached timetable of a class that no longer
 * existed, never reached the join screen, and therefore never ran the wipe
 * that joining does on the way in; the old class was still on screen after
 * joining a new one with a new code.
 *
 * A `401` on the class bearer has exactly one correct answer — the token is
 * gone server-side and cannot come back — so it has to be automatic. Every
 * other failure must leave the session alone: no network is not "you were
 * thrown out", and neither is a role that changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenRejectedTest {

    // -- the read API -------------------------------------------------------

    @Test
    fun `a 401 on a sync drops the session`() = runTest {
        var rejected = 0
        val result = refreshAgainst(FailingApi(httpError(401)), onTokenRejected = { rejected++ })

        assertEquals(SyncResult.Unauthorised, result)
        assertEquals(1, rejected)
    }

    @Test
    fun `a 500 does not, because the token is still good`() = runTest {
        var rejected = 0
        val result = refreshAgainst(FailingApi(httpError(500)), onTokenRejected = { rejected++ })

        assertTrue(result is SyncResult.Failed)
        assertEquals(0, rejected)
    }

    @Test
    fun `no network does not sign anybody out`() = runTest {
        var rejected = 0
        val result = refreshAgainst(
            FailingApi(IOException("airplane mode")),
            onTokenRejected = { rejected++ },
        )

        assertTrue(result is SyncResult.Failed)
        assertEquals(0, rejected)
    }

    private suspend fun refreshAgainst(
        api: LessonsApi,
        onTokenRejected: suspend () -> Unit,
    ): SyncResult = TimetableRepositoryImpl(
        dao = InMemoryTimetableDao(),
        api = api,
        // Which class is on screen is beside the point here: a 401 is about the
        // token, and the token is the same one whichever class it names.
        activeClassId = flowOf(1L),
        ioDispatcher = UnconfinedTestDispatcher(),
        onTokenRejected = onTokenRejected,
    ).refresh()

    // -- the management API -------------------------------------------------

    @Test
    fun `a 401 on the management surface drops the session too`() = runTest {
        var rejected = 0
        val result = ManageRepositoryImpl(
            api = FailingManageApi(httpError(401)),
            ioDispatcher = UnconfinedTestDispatcher(),
            onTokenRejected = { rejected++ },
        ).classCard()

        assertTrue(result.exceptionOrNull() is ManageFailure.SignedOut)
        assertEquals(1, rejected)
    }

    @Test
    fun `a demoted role is not a lost token`() = runTest {
        // The device is still in the class; only what it may do has changed.
        // Signing out here would throw an editor off the app for opening the
        // one sheet their role no longer covers.
        var rejected = 0
        val result = ManageRepositoryImpl(
            api = FailingManageApi(httpError(403, """{"detail":"admin role required"}""")),
            ioDispatcher = UnconfinedTestDispatcher(),
            onTokenRejected = { rejected++ },
        ).classCard()

        assertTrue(result.exceptionOrNull() is ManageFailure.RoleLost)
        assertEquals(0, rejected)
    }

    @Test
    fun `an unlinked phone is not a lost token either`() = runTest {
        var rejected = 0
        val result = ManageRepositoryImpl(
            api = FailingManageApi(httpError(403, """{"detail":"device is not linked"}""")),
            ioDispatcher = UnconfinedTestDispatcher(),
            onTokenRejected = { rejected++ },
        ).classCard()

        assertTrue(result.exceptionOrNull() is ManageFailure.NotLinked)
        assertEquals(0, rejected)
    }

    @Test
    fun `a successful call rejects nothing`() = runTest {
        var rejected = 0
        val result = ManageRepositoryImpl(
            api = object : ManageApi by UnusedManageApi() {
                override suspend fun classCard() = ManagedClassDto(id = 1, name = "9А")
            },
            ioDispatcher = UnconfinedTestDispatcher(),
            onTokenRejected = { rejected++ },
        ).classCard()

        assertTrue(result.isSuccess)
        assertFalse(rejected > 0)
    }

    // -- fakes --------------------------------------------------------------

    private fun httpError(code: Int, body: String = """{"detail":"no"}"""): HttpException {
        val request = Request.Builder().url("https://school.example/api/v1/bundle").build()
        val raw = okhttp3.Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .build()
        return HttpException(
            Response.error<Unit>(body.toResponseBody("application/json".toMediaType()), raw),
        )
    }

    private class FailingApi(private val failure: Exception) : LessonsApi by UnusedApi() {
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?): Nothing =
            throw failure
    }

    private class UnusedApi : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?) =
            error("unused")
        override suspend fun health() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")
    }

    private class FailingManageApi(private val failure: Exception) : ManageApi by UnusedManageApi() {
        override suspend fun classCard(): Nothing = throw failure
    }
}

/** Every management call these tests do not make; reaching one is the bug. */
internal class UnusedManageApi : ManageApi {
    override suspend fun classCard() = error("unused")
    override suspend fun updateClass(body: ClassPatchDto) = error("unused")
    override suspend fun deleteClass(body: com.lumenpearson.lessons.core.data.network.dto.ClassDeleteDto) =
        error("unused")
    override suspend fun searchSchools(query: String, pageSize: Int, region: String?) = error("unused")
    override suspend fun subjects() = error("unused")
    override suspend fun createSubject(body: com.lumenpearson.lessons.core.data.network.dto.SubjectInDto) =
        error("unused")
    override suspend fun updateSubject(
        subjectId: Long,
        body: com.lumenpearson.lessons.core.data.network.dto.SubjectPatchDto,
    ) = error("unused")
    override suspend fun deleteSubject(subjectId: Long) = error("unused")
    override suspend fun bells() = error("unused")
    override suspend fun createBellSchedule(
        body: com.lumenpearson.lessons.core.data.network.dto.BellScheduleInDto,
    ) = error("unused")
    override suspend fun updateBellSchedule(
        scheduleId: Long,
        body: com.lumenpearson.lessons.core.data.network.dto.BellSchedulePatchDto,
    ) = error("unused")
    override suspend fun writeBellPeriods(
        scheduleId: Long,
        body: com.lumenpearson.lessons.core.data.network.dto.BellPeriodsDto,
    ) = error("unused")
    override suspend fun deleteBellSchedule(scheduleId: Long) = error("unused")
    override suspend fun timetable() = error("unused")
    override suspend fun importTimetable(
        body: com.lumenpearson.lessons.core.data.network.dto.TimetableImportInDto,
    ) = error("unused")
    override suspend fun devices(includeRevoked: Boolean) = error("unused")
    override suspend fun revokeDevice(deviceId: Long) = error("unused")
    override suspend fun unlinkDevice(deviceId: Long) = error("unused")
    override suspend fun log(limit: Int, offset: Int) = error("unused")
    override suspend fun stats() = error("unused")
    override suspend fun requests() = error("unused")
    override suspend fun approveRequest(
        requestId: Long,
        body: com.lumenpearson.lessons.core.data.network.dto.RequestDecisionInDto,
    ) = error("unused")
    override suspend fun declineRequest(requestId: Long) = error("unused")
}

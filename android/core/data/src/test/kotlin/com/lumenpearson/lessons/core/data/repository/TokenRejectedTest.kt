package com.lumenpearson.lessons.core.data.repository

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.network.v2.RemoteError

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.v2.ManageRemote
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.toManagedClass
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
 * V2 sync requires DEVICE_TOKEN_INVALID; a bare proxy 401 is not proof that
 * membership was revoked. Management retains its v1 status contract until5b.
 * No network or changed role may remove memberships.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenRejectedTest {

    // -- the read API -------------------------------------------------------

    @Test
    fun `DEVICE_TOKEN_INVALID on a sync drops the session`() = runTest {
        var rejected = 0
        val result = refreshAgainst(
            FailingApi(RemoteError(Code.UNAUTHENTICATED, "DEVICE_TOKEN_INVALID", httpStatus = 401)),
            onTokenRejected = { rejected++ },
        )

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
        remote = api.scheduleFixture(),
        // Which class is on screen is beside the point here: a 401 is about the
        // token, and the token is the same one whichever class it names.
        activeClassId = flowOf(1L),
        ioDispatcher = UnconfinedTestDispatcher(),
        onTokenRejected = onTokenRejected,
    ).refresh()

    // -- the management API -------------------------------------------------

    @Test
    fun `an observed invalid-token reason on management rejects its sent membership`() = runTest {
        var rejected = 0
        val result = management(RemoteError(Code.UNAUTHENTICATED, "DEVICE_TOKEN_INVALID", httpStatus = 401)) {
            rejected++
        }
        assertTrue(result.exceptionOrNull() is ManageFailure.SignedOut)
        assertEquals(1, rejected)
    }

    @Test
    fun `a demoted role is not a lost token`() = runTest {
        var rejected = 0
        val result = management(RemoteError(Code.PERMISSION_DENIED, "ROLE_REQUIRED",
            mapOf("role" to "ROLE_ADMIN"), httpStatus = 403)) { rejected++ }
        assertTrue(result.exceptionOrNull() is ManageFailure.RoleLost)
        assertEquals(0, rejected)
    }

    @Test
    fun `an unlinked phone is not a lost token either`() = runTest {
        var rejected = 0
        val result = management(RemoteError(Code.FAILED_PRECONDITION, "DEVICE_NOT_LINKED", httpStatus = 400)) {
            rejected++
        }
        assertTrue(result.exceptionOrNull() is ManageFailure.NotLinked)
        assertEquals(0, rejected)
    }

    @Test
    fun `a reasonless management 401 is unexpected and cannot end the membership`() = runTest {
        var rejected = 0
        val result = management(RemoteError(Code.UNAUTHENTICATED, httpStatus = 401)) { rejected++ }
        assertTrue(result.exceptionOrNull() is ManageFailure.Unexpected)
        assertEquals(0, rejected)
    }

    @Test
    fun `a successful call rejects nothing`() = runTest {
        var rejected = 0
        val result = management(null) { rejected++ }
        assertTrue(result.isSuccess)
        assertFalse(rejected > 0)
    }

    private suspend fun management(failure: Exception?, rejected: suspend () -> Unit): Result<ManagedClass> {
        val fixture = V2InFlightMembershipTest.Fixture()
        fixture.seed()
        val remote = object : ManageRemote by UnusedManageRemote {
            override suspend fun classCard(observation: CallObservation): ManagedClass {
                observation.recordRequest(fixture.preferences.currentClassRequest().second)
                failure?.let { throw it }
                return SchoolClassDto(id = 7, name = "9A", timezone = "Europe/Moscow").toManagedClass()
            }
        }
        return ManageRepositoryImpl(remote, UnconfinedTestDispatcher(),
            ScheduleMembership(fixture.preferences) { request ->
                rejected()
                fixture.sessions.rejectRequest(request)
            }).classCard()
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
        override suspend fun warmup() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")
    }

}

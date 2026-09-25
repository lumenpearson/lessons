package com.lumenpearson.lessons.core.data.repository

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The one rule the whole diary section hangs on: a 401 is two different
 * answers, and the header is what tells them apart.
 *
 * `server/app/api/diary.py` sends `X-Diary-Reauth: required` when the *upstream*
 * session died, and a bare 401 when our own token is not good. The first asks
 * for a password, the second for a login and a password, and an app that reads
 * the message instead of the header shows the wrong one of those two screens
 * every time the diary logs somebody out.
 */
class DiaryFailureTest {

    @Test
    fun `a bare 401 asks for a full sign-in`() {
        assertEquals(DiaryFailure.SignInRequired, DiaryFailure.of(httpError(401)))
    }

    @Test
    fun `a 401 carrying the re-auth header asks only for the password`() {
        assertEquals(
            DiaryFailure.ReauthRequired,
            DiaryFailure.of(httpError(401, DiaryFailure.REAUTH_HEADER to "required")),
        )
    }

    /** The header is a value, not a presence: anything else is a plain 401. */
    @Test
    fun `an unrelated value in the re-auth header is not a re-auth`() {
        assertEquals(
            DiaryFailure.SignInRequired,
            DiaryFailure.of(httpError(401, DiaryFailure.REAUTH_HEADER to "no")),
        )
    }

    @Test
    fun `503 is the diary being down`() {
        assertEquals(DiaryFailure.Unavailable, DiaryFailure.of(httpError(503)))
    }

    @Test
    fun `502 is the diary having changed shape`() {
        assertEquals(DiaryFailure.Unreadable, DiaryFailure.of(httpError(502)))
    }

    @Test
    fun `404 is a pupil this account may not see`() {
        assertEquals(DiaryFailure.UnknownStudent, DiaryFailure.of(httpError(404)))
    }

    @Test
    fun `422 is a date range this app should not have asked for`() {
        assertEquals(DiaryFailure.BadRange, DiaryFailure.of(httpError(422)))
    }

    /**
     * The same status on `/overrides` is the server refusing to file a
     * correction, and the response says so only in a Russian `detail` — reading
     * which is the text-parsing this class exists to replace. So the caller
     * names the meaning and the default stays the one the reads want.
     */
    @Test
    fun `422 means a refused correction when the caller says so`() {
        assertEquals(
            DiaryFailure.Rejected,
            DiaryFailure.of(httpError(422), unprocessable = DiaryFailure.Rejected),
        )
    }

    @Test
    fun `a status with no rule of its own keeps its code`() {
        val failure = DiaryFailure.of(httpError(418))
        assertTrue(failure is DiaryFailure.Unexpected)
        assertEquals(418, (failure as DiaryFailure.Unexpected).code)
    }

    /** No answer at all is not a status code, and must not read as one. */
    @Test
    fun `a network error is offline rather than a status`() {
        val failure = DiaryFailure.of(SocketTimeoutException("timeout"))
        assertTrue(failure is DiaryFailure.Offline)
        assertTrue((failure as DiaryFailure.Offline).reason is IOException)
    }

    /**
     * The server said «слишком много попыток» and how long to wait; the app
     * used to answer «Не получилось: 429» (#153).
     */
    @Test
    fun `429 is a throttle carrying the wait`() {
        assertEquals(
            DiaryFailure.Throttled(retryAfterSeconds = 120),
            DiaryFailure.of(httpError(429, "Retry-After" to "120")),
        )
    }

    @Test
    fun `429 without a readable wait is still a throttle`() {
        assertEquals(
            DiaryFailure.Throttled(retryAfterSeconds = null),
            DiaryFailure.of(httpError(429, "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")),
        )
    }

    /** «Выключен на сервере» and «не отвечает» are both 503s; the header tells them apart (#153). */
    @Test
    fun `503 marked disabled is the diary switched off, not down`() {
        assertEquals(
            DiaryFailure.Disabled,
            DiaryFailure.of(httpError(503, DiaryFailure.UNAVAILABLE_HEADER to "disabled")),
        )
    }

    @Test
    fun `503 marked address-refused is the diary refusing our server`() {
        assertEquals(
            DiaryFailure.ServerAddressRefused,
            DiaryFailure.of(httpError(503, DiaryFailure.UNAVAILABLE_HEADER to "address-refused")),
        )
    }

    @Test
    fun `503 marked upstream is the diary being down`() {
        assertEquals(
            DiaryFailure.Unavailable,
            DiaryFailure.of(httpError(503, DiaryFailure.UNAVAILABLE_HEADER to "upstream")),
        )
    }

    /** The platform's 30-second ceiling: slow, not wrong, so worth another try (#153). */
    @Test
    fun `504 is the diary being slow, not an unexpected status`() {
        assertEquals(DiaryFailure.Unavailable, DiaryFailure.of(httpError(504)))
    }

    /** Classifying twice must not wrap a classification in another one. */
    @Test
    fun `an already classified failure passes through`() {
        assertEquals(DiaryFailure.ReauthRequired, DiaryFailure.of(DiaryFailure.ReauthRequired))
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
                """{"detail":"whatever the server said"}"""
                    .toResponseBody("application/json".toMediaType()),
                raw,
            ),
        )
    }
}

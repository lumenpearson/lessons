package com.lumenpearson.lessons.core.data.repository

import java.io.IOException
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
 * The three refusals `POST /api/v1/join` can give to a code that was typed in
 * correctly, and why none of them may be the same sentence as another.
 *
 * A `404` means no class answers to this code, and the thing to do about it is
 * to check the code with whoever handed it out. A `403` means the code names a
 * real class that has stopped admitting anybody who merely knows it — and the
 * thing to do about it is to open the bot, which the person holding the code
 * would never think of if they were told the code was wrong. A `429` means the
 * code was never looked at, and the thing to do about it is to wait, which is
 * the one answer the server can put a number on. Collapsing any of them is the
 * bug this file exists to keep out: it is invisible on screen, because the
 * wrong sentence is a perfectly reasonable sentence.
 */
class JoinFailureTest {

    @Test
    fun `a 403 is the class asking for a bot invite`() {
        val failure = JoinFailure.of(
            httpError(403, """{"detail":"Этот класс принимает только по личному приглашению из бота"}"""),
        )

        assertEquals(JoinFailure.InviteOnly, failure)
    }

    @Test
    fun `a 404 is still an unknown code`() {
        val failure = JoinFailure.of(httpError(404, """{"detail":"Unknown join code"}"""))

        assertEquals(JoinFailure.UnknownCode, failure)
    }

    @Test
    fun `a 429 carries the wait the server put a number on`() {
        val failure = JoinFailure.of(
            httpError(429, """{"detail":"Too many join attempts"}""", retryAfter = "901"),
        )

        assertEquals(JoinFailure.TooManyAttempts(retryAfterSeconds = 901), failure)
    }

    @Test
    fun `a 429 with no usable Retry-After is still a wait, just an unnumbered one`() {
        // A proxy may strip the header, and nothing says it has to be a number.
        // The HTTP-date form is legal and this server never sends it; reading
        // it against a phone clock that may be wrong would turn a missing
        // header — which the screen already handles — into a confident lie.
        for (header in listOf(null, "", "soon", "0", "-5", "Wed, 21 Oct 2026 07:28:00 GMT")) {
            val failure = JoinFailure.of(
                httpError(429, """{"detail":"Too many join attempts"}""", retryAfter = header),
            )

            assertEquals(
                "Retry-After: ${header ?: "absent"}",
                JoinFailure.TooManyAttempts(retryAfterSeconds = null),
                failure,
            )
        }
    }

    /**
     * Every `5xx` stays «вот что сказал сервер», with the message the exception
     * carried — which is what the screen printed for all of these before the
     * three cases above were split out of them.
     */
    @Test
    fun `anything else keeps the message it came with`() {
        val thrown = httpError(503, """{"detail":"upstream is down"}""")

        val failure = JoinFailure.of(thrown)

        assertTrue(failure is JoinFailure.Rejected)
        assertEquals(503, (failure as JoinFailure.Rejected).code)
        assertEquals(thrown.message, failure.message)
    }

    @Test
    fun `no network is not a wrong code`() {
        val failure = JoinFailure.of(IOException("airplane mode"))

        assertTrue(failure is JoinFailure.Offline)
        assertEquals("airplane mode", failure.message)
    }

    private fun httpError(code: Int, body: String, retryAfter: String? = null): HttpException {
        val request = Request.Builder().url("https://school.example/api/v1/join").build()
        val raw = okhttp3.Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .build()
        return HttpException(
            Response.error<Unit>(body.toResponseBody("application/json".toMediaType()), raw),
        )
    }
}

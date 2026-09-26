package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem.Action
import com.lumenpearson.lessons.core.data.upstream.UpstreamFailure
import com.lumenpearson.lessons.core.data.upstream.UpstreamNotAllowed
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * The one failure vocabulary the sign-in, the school search and the import
 * screens switch on: every way in maps to exactly one problem and one action.
 *
 * The rows marked #153 are the phone's half of that issue — a 429 that read
 * «Не получилось: 429», a «switched off» 503 that read «не отвечает», and a
 * Vercel 504 that read as an unknown status.
 */
class DiarySignInProblemTest {

    private val host = "region.zabedu.ru"

    @Test
    fun `every failure maps to one problem and one action`() {
        val table: List<Triple<String, Throwable, Pair<DiarySignInProblem, Action>>> = listOf(
            // The diary's own server.
            row("wrong password", UpstreamFailure.BadCredentials("Неверный пароль"),
                DiarySignInProblem.WrongPassword("Неверный пароль"), Action.RETYPE),
            row("Госуслуги only", UpstreamFailure.SignInUnsupported(),
                DiarySignInProblem.GosuslugiOnly(null), Action.HANDOFF),
            row("diary down", UpstreamFailure.Unavailable(IOException("reset")),
                DiarySignInProblem.ProviderUnavailable(host), Action.RETRY),
            row("diary slow", UpstreamFailure.Unavailable(SocketTimeoutException("timeout")),
                DiarySignInProblem.Timeout(host), Action.RETRY),
            row("diary unreachable", UpstreamFailure.Offline(IOException("dns")),
                DiarySignInProblem.ProviderOffline(host), Action.RETRY),
            row("diary refuses the phone", UpstreamFailure.AddressRefused(),
                DiarySignInProblem.ProviderRefusesPhone(host), Action.RETRY),
            row("certificate", UpstreamFailure.Untrusted(SSLHandshakeException("PKIX")),
                DiarySignInProblem.ProviderUntrusted(host), Action.NONE),
            row("captcha", UpstreamFailure.Unexpected("html"),
                DiarySignInProblem.ProviderUnreadable(host), Action.NONE),
            // Our server.
            row("no address", ServerAddressMissingException(), DiarySignInProblem.ServerMissing, Action.SET_SERVER),
            row("no network", IOException("unreachable"), DiarySignInProblem.Offline, Action.RETRY),
            row("socket timeout", SocketTimeoutException("timeout"), DiarySignInProblem.Timeout(null), Action.RETRY),
            row("call timeout", InterruptedIOException("timeout"), DiarySignInProblem.Timeout(null), Action.RETRY),
            row("401", http(401), DiarySignInProblem.SignInRequired, Action.RETYPE),
            row("401 re-auth", http(401, DiaryFailure.REAUTH_HEADER to "required"),
                DiarySignInProblem.ReauthRequired, Action.RETYPE),
            row("409", http(409), DiarySignInProblem.ServerRefusedSession, Action.NONE),
            row("429 (#153)", http(429, "Retry-After" to "90"), DiarySignInProblem.TooManyAttempts(90), Action.WAIT),
            row("502", http(502), DiarySignInProblem.ProviderUnreadable(null), Action.NONE),
            row("503 disabled (#153)", http(503, DiaryFailure.UNAVAILABLE_HEADER to "disabled"),
                DiarySignInProblem.ServerDisabled, Action.NONE),
            row("503 address-refused (#153)", http(503, DiaryFailure.UNAVAILABLE_HEADER to "address-refused"),
                DiarySignInProblem.ServerAddressRefused, Action.NONE),
            row("503 upstream", http(503, DiaryFailure.UNAVAILABLE_HEADER to "upstream"),
                DiarySignInProblem.ProviderUnavailable(null), Action.RETRY),
            row("bare 503", http(503), DiarySignInProblem.ProviderUnavailable(null), Action.RETRY),
            row("504 (#153)", http(504), DiarySignInProblem.Timeout(null), Action.RETRY),
            // What reads already classified.
            row("read throttled", DiaryFailure.Throttled(12), DiarySignInProblem.TooManyAttempts(12), Action.WAIT),
            row("read disabled", DiaryFailure.Disabled, DiarySignInProblem.ServerDisabled, Action.NONE),
            row("read address refused", DiaryFailure.ServerAddressRefused, DiarySignInProblem.ServerAddressRefused, Action.NONE),
            row("read re-auth", DiaryFailure.ReauthRequired, DiarySignInProblem.ReauthRequired, Action.RETYPE),
            row("read signed out", DiaryFailure.SignInRequired, DiarySignInProblem.SignInRequired, Action.RETYPE),
            row("read down", DiaryFailure.Unavailable, DiarySignInProblem.ProviderUnavailable(null), Action.RETRY),
            row("read unreadable", DiaryFailure.Unreadable, DiarySignInProblem.ProviderUnreadable(null), Action.NONE),
            row("read offline", DiaryFailure.Offline(IOException("x")), DiarySignInProblem.Offline, Action.RETRY),
            row("read offline, no address", DiaryFailure.Offline(ServerAddressMissingException()),
                DiarySignInProblem.ServerMissing, Action.SET_SERVER),
        )
        for ((label, thrown, expected) in table) {
            val problem = DiarySignInProblem.of(thrown, host = if (thrown is UpstreamFailure) host else null)
            assertEquals(label, expected.first, problem)
            assertEquals(label, expected.second, problem.action)
        }
    }

    /** A 422 is a body the server refused before asking anybody: a bug here, never «retype». */
    @Test
    fun `422 is unexpected, not a password to retype`() {
        val problem = DiarySignInProblem.of(http(422), registering = true)
        assertTrue(problem is DiarySignInProblem.Unexpected)
        assertEquals(Action.RETRY, problem.action)
    }

    @Test
    fun `403 is an account with no pupil on registration only`() {
        assertEquals(DiarySignInProblem.NoStudent, DiarySignInProblem.of(http(403), registering = true))
        assertTrue(DiarySignInProblem.of(http(403)) is DiarySignInProblem.Unexpected)
    }

    /** The diary let the family in; our server was the one out of reach. The session is still held. */
    @Test
    fun `a network failure while registering is not a network failure before anything happened`() {
        val problem = DiarySignInProblem.of(IOException("reset"), registering = true)
        assertEquals(DiarySignInProblem.RegisterUnreachable, problem)
        assertTrue(problem.retryKeepsSession)
    }

    @Test
    fun `a host outside the allow-list is a bug, never no network`() {
        val problem = DiarySignInProblem.of(UpstreamNotAllowed("https://evil.example/".toHttpUrl()))
        assertTrue(problem is DiarySignInProblem.Unexpected)
    }

    @Test
    fun `only a failure nobody judged keeps the session for a retry`() {
        val kept = listOf(
            DiarySignInProblem.RegisterUnreachable,
            DiarySignInProblem.Timeout(null),
            DiarySignInProblem.ProviderUnavailable(null),
            DiarySignInProblem.TooManyAttempts(10),
        )
        val dropped = listOf(
            DiarySignInProblem.ServerRefusedSession,
            DiarySignInProblem.NoStudent,
            DiarySignInProblem.ServerDisabled,
            DiarySignInProblem.ServerAddressRefused,
            DiarySignInProblem.ProviderUnreadable(null),
            DiarySignInProblem.SessionAgedOut,
            DiarySignInProblem.Unexpected("422"),
        )
        kept.forEach { assertTrue(it.toString(), it.retryKeepsSession) }
        dropped.forEach { assertTrue(it.toString(), !it.retryKeepsSession) }
    }

    @Test
    fun `an already classified problem passes through`() {
        assertEquals(DiarySignInProblem.NoStudent, DiarySignInProblem.of(DiarySignInProblem.NoStudent))
    }

    private fun row(label: String, thrown: Throwable, problem: DiarySignInProblem, action: Action) =
        Triple(label, thrown, problem to action)

    private fun http(code: Int, vararg headers: Pair<String, String>): HttpException {
        val request = Request.Builder().url("https://school.example/api/v1/diary/session").build()
        val raw = okhttp3.Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return HttpException(
            Response.error<Unit>("""{"detail":"no"}""".toResponseBody("application/json".toMediaType()), raw),
        )
    }
}

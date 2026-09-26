package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.network.AuthInterceptor
import com.lumenpearson.lessons.core.data.network.BaseUrlInterceptor
import com.lumenpearson.lessons.core.data.network.DiaryAuthInterceptor
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The client the phone talks to the diaries with, and the rules that make it
 * safe to hand a password to: one allow-list, no redirects, no cookie jar, no
 * bearer of ours, and every transport failure turned into what it means.
 */
class UpstreamHttpTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * First, because every other test in this package stands on it: OkHttp's
     * Android build asks `android.util.Log` which platform it is on, and a JVM
     * unit test has only the stub. If this fails, nothing else here means
     * anything.
     */
    @Test
    fun `a real call reaches MockWebServer from a JVM unit test`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("hello").build())

        val answer = UpstreamHttp.fetch(clientFor(server), Request.Builder().url(server.url("/ping")).build())

        assertEquals(200, answer.code)
        assertEquals("hello", answer.body)
        assertEquals("/ping", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an origin outside the allow-list is refused before a socket opens`() = runBlocking {
        val elsewhere = UpstreamHttp.client { setOf(UpstreamOrigin("https", "region.zabedu.ru", 443)) }

        try {
            UpstreamHttp.fetch(elsewhere, Request.Builder().url(server.url("/webapi/logindata")).build())
            fail("a host outside the allow-list was reached")
        } catch (refused: UpstreamNotAllowed) {
            // expected
        }
        assertEquals(0, server.requestCount)
    }

    /** The port is part of the origin: the same host on another port is another server. */
    @Test
    fun `the same host on another port is not allowed`() = runBlocking {
        val url = server.url("/")
        val otherPort = UpstreamHttp.client { setOf(UpstreamOrigin(url.scheme, url.host, url.port + 1)) }

        val failure = runCatching {
            UpstreamHttp.fetch(otherPort, Request.Builder().url(server.url("/")).build())
        }.exceptionOrNull()

        assertTrue(failure is UpstreamNotAllowed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the provider client carries none of the school server's interceptors`() {
        val client = clientFor(server)

        val kinds = client.interceptors.map { it::class }
        assertEquals(listOf(OriginGuard::class, BrowserHeaders::class), kinds)
        for (ours in listOf(BaseUrlInterceptor::class, AuthInterceptor::class, DiaryAuthInterceptor::class)) {
            assertFalse(ours in kinds)
        }
        assertTrue(client.networkInterceptors.isEmpty())
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
    }

    @Test
    fun `a browser user agent is sent and an Authorization header is refused`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        UpstreamHttp.fetch(clientFor(server), Request.Builder().url(server.url("/")).build())
        assertEquals(UpstreamHttp.USER_AGENT, server.takeRequest().headers["User-Agent"])

        val signed = Request.Builder().url(server.url("/")).header("Authorization", "Bearer ours").build()
        val failure = runCatching { UpstreamHttp.fetch(clientFor(server), signed) }.exceptionOrNull()
        assertTrue(failure is UpstreamNotAllowed)
        assertEquals(1, server.requestCount)
    }

    /**
     * Two families on one phone, or one family twice: the first answer's
     * cookie must not ride out on the second request, which is how two
     * sessions meet.
     */
    @Test
    fun `two sign-ins never share a cookie`() = runBlocking {
        val client = clientFor(server)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "NSSESSIONID=first; Path=/").build())
        server.enqueue(MockResponse.Builder().code(200).build())

        UpstreamHttp.fetch(client, Request.Builder().url(server.url("/webapi/logindata")).build())
        UpstreamHttp.fetch(client, Request.Builder().url(server.url("/webapi/logindata")).build())

        server.takeRequest()
        assertNull(server.takeRequest().headers["Cookie"])
    }

    /** A redirect is the one way around an allow-list, so it is read, never followed. */
    @Test
    fun `a redirect is not followed`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example/steal").build(),
        )

        val answer = UpstreamHttp.fetch(clientFor(server), Request.Builder().url(server.url("/")).build())

        assertEquals(302, answer.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a certificate the phone cannot trust is untrusted, never offline`() = runBlocking {
        // Android's shape: the handshake fails because a CertificateException
        // wraps the path validator's «Trust anchor for certification path not found».
        val client = clientFor(server).newBuilder()
            .addInterceptor { throw handshake("Trust anchor", CertificateException(CertPathValidatorException("no anchor"))) }
            .build()

        val failure = runCatching {
            UpstreamHttp.fetch(client, Request.Builder().url(server.url("/")).build())
        }.exceptionOrNull()

        assertTrue("got $failure", failure is UpstreamFailure.Untrusted)
    }

    /**
     * Only a failure that examined the certificate is a trust problem. The
     * JVM's own «PKIX path building failed» carries a CertificateException
     * subclass, an expired certificate is one, and a name the certificate does
     * not cover is OkHttp's SSLPeerUnverifiedException.
     */
    @Test
    fun `every shape of a real trust failure is untrusted`() {
        val trust = listOf(
            handshake("PKIX path building failed", CertificateException("PKIX", CertPathBuilderException("unable to find path"))),
            handshake("expired", CertificateExpiredException("NotAfter: 2020")),
            handshake("validator", CertPathValidatorException("revoked")),
            SSLPeerUnverifiedException("Hostname region.zabedu.ru not verified"),
            IOException("wrapped", SSLPeerUnverifiedException("peer not authenticated")),
        )
        for (failure in trust) {
            assertTrue("$failure", UpstreamHttp.classify(failure) is UpstreamFailure.Untrusted)
        }
    }

    /**
     * A handshake the network broke never looked at a certificate: Conscrypt
     * says «Connection reset by peer» or «Connection closed by peer» for a
     * firewall or a flaky link, the JVM «Remote host terminated the handshake»
     * over an EOF. Calling that «the phone does not trust the diary» offered no
     * retry for something a retry fixes.
     */
    @Test
    fun `a handshake the network broke is unavailable, not untrusted`() {
        val broken = listOf(
            handshake("Read error: ssl=0x7b: I/O error during system call, Connection reset by peer", SocketException("Connection reset")),
            handshake("Connection closed by peer", null),
            handshake("Remote host terminated the handshake", EOFException("SSL peer shut down incorrectly")),
            handshake("Received fatal alert: handshake_failure", null),
        )
        for (failure in broken) {
            val classified = UpstreamHttp.classify(failure)
            assertTrue("$failure: $classified", classified is UpstreamFailure.Unavailable)
        }
    }

    @Test
    fun `a timeout is unavailable and says it timed out`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build())
        val impatient = clientFor(server).newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()

        val failure = runCatching {
            UpstreamHttp.fetch(impatient, Request.Builder().url(server.url("/")).build())
        }.exceptionOrNull()

        assertTrue("got $failure", failure is UpstreamFailure.Unavailable)
        assertTrue((failure as UpstreamFailure.Unavailable).timedOut)
    }

    /**
     * On a phone a refused connection is its own network far more often than
     * a regional firewall — see `NetSchoolSignIn` — so it is «try again», not
     * «this diary refuses you».
     */
    @Test
    fun `a refused connection on the phone is unavailable, not address refused`() = runBlocking {
        val url = server.url("/webapi/logindata")
        val client = clientFor(server)
        server.close()

        val failure = runCatching { UpstreamHttp.fetch(client, Request.Builder().url(url).build()) }.exceptionOrNull()

        assertTrue("got $failure", failure is UpstreamFailure.Unavailable)
        assertFalse((failure as UpstreamFailure.Unavailable).timedOut)
    }

    @Test
    fun `a name that does not resolve is offline`() {
        val failure = UpstreamHttp.classify(IOException("lookup", UnknownHostException("sgo.example")))
        assertTrue(failure is UpstreamFailure.Offline)
    }

    private fun handshake(message: String, cause: Throwable?): SSLHandshakeException =
        SSLHandshakeException(message).apply { if (cause != null) initCause(cause) }

    @Test
    fun `a session cookie is read from the last live one of its name`() {
        val url = "https://region.zabedu.ru/webapi/login".toHttpUrl()
        val headers = okhttp3.Headers.headersOf(
            "Set-Cookie", "NSSESSIONID=old; Path=/",
            "Set-Cookie", "ESRNSec=sec; Path=/",
            "Set-Cookie", "NSSESSIONID=new; Path=/webapi",
            "Set-Cookie", "NSSESSIONID=; Max-Age=0; Path=/",
        )

        assertEquals("new", UpstreamHttp.sessionCookie(url, headers, "NSSESSIONID", 0))
        assertEquals("sec", UpstreamHttp.sessionCookie(url, headers, "ESRNSec", 0))
        assertNull(UpstreamHttp.sessionCookie(url, headers, "X-JWT-Token", 0))
    }
}

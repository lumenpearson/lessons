package com.lumenpearson.lessons.core.data.developer

import com.lumenpearson.lessons.core.data.repository.ServerStatus
import com.lumenpearson.lessons.core.data.upstream.UpstreamFailure
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The developer page's checks (#237), as far as the JVM can ask them: every
 * check answers within its deadline whatever it does, and a failure reads as
 * what it was. The checks that need a phone — the Keystore, the network, the
 * permissions — are what a device is for.
 */
class DeveloperChecksTest {

    @Test
    fun `a check that never answers is a failure after its deadline, not a page that waits`() = runTest {
        val result = checked(CheckKind.DIARY_HOST, subject = "dnevnik2.petersburgedu.ru") { awaitCancellation() }

        assertEquals(CheckOutcome.FAIL, result.outcome)
        assertEquals("no answer in ${CheckBudgetMillis / 1_000} s", result.detail)
        assertEquals("dnevnik2.petersburgedu.ru", result.subject)
    }

    @Test
    fun `a check that throws reads as the failure and its cause`() = runTest {
        val result = checked(CheckKind.DIARY_HOST) {
            throw UpstreamFailure.Unavailable(SocketTimeoutException("timeout"))
        }

        assertEquals(CheckOutcome.FAIL, result.outcome)
        assertEquals("Unavailable (SocketTimeoutException)", result.detail)
    }

    @Test
    fun `a failure with no cause is named alone`() {
        assertEquals("IOException", describe(IOException("reset")))
    }

    @Test
    fun `an answer is passed through with its time`() = runTest {
        val result = checked(CheckKind.GITHUB) { CheckOutcome.PASS to "HTTP 200" }

        assertEquals(CheckOutcome.PASS, result.outcome)
        assertEquals("HTTP 200", result.detail)
        assertEquals(CheckKind.GITHUB, result.kind)
    }

    @Test
    fun `the server's own answer maps onto the four verdicts`() {
        assertEquals(CheckOutcome.PASS, serverAnswer(ServerStatus.Ok(apiVersion = 1, schema = "0017")).first)
        assertEquals(CheckOutcome.WARN, serverAnswer(ServerStatus.Degraded("0016", "0017", null)).first)
        assertEquals(CheckOutcome.FAIL, serverAnswer(ServerStatus.Unreachable).first)
        assertEquals(CheckOutcome.FAIL, serverAnswer(ServerStatus.NeedsHttps).first)
        assertEquals(CheckOutcome.SKIP, serverAnswer(ServerStatus.NotConfigured).first)
        assertEquals("api 1 · schema 0017", serverAnswer(ServerStatus.Ok(apiVersion = 1, schema = "0017")).second)
    }
}

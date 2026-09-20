package com.lumenpearson.lessons.core.data.github

import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What ends the device flow, and what must not.
 *
 * The user is in a browser on another device typing a code, and this loop is
 * the only thing that will ever fetch the token their typing produces. It runs
 * for up to a quarter of an hour on a school network, so «the request did not
 * arrive» is an ordinary event inside it and not an answer to anything: the
 * authorisation may already exist on GitHub's side, and a loop that stops on it
 * leaves that authorisation listed under the user's applications with no token
 * on the phone — and the sheet says «не удалось» to somebody who did
 * everything right.
 *
 * Every test here drives the loop directly. There is no way to see the
 * difference between a chain that is waiting and a chain that has stopped from
 * outside it: the sheet shows the same code either way.
 */
class DeviceFlowPollTest {

    /** Answers the given outcomes in order; a `null` entry throws instead. */
    private class Poller(private vararg val answers: PollOutcome?) {
        var calls = 0

        suspend fun poll(): PollOutcome {
            val answer = answers.getOrNull(calls)
            calls += 1
            return answer ?: throw SocketTimeoutException("timeout")
        }
    }

    @Test
    fun `a request that never arrived is not a verdict`() = runTest {
        // The blip: one poll fails at the transport, the next one is answered.
        // Before, the exception left `pollUntilDecided` altogether and the
        // whole sign-in ended as «failed» — for a device code that was still
        // perfectly good, and often already authorised.
        val poller = Poller(null, PollOutcome.Pending, PollOutcome.Granted("gho_token"))

        val verdict = pollUntilDecided(
            interval = 5,
            expiresAt = Long.MAX_VALUE / 2,
            now = { 0L },
            poll = poller::poll,
        )

        assertEquals(PollVerdict.Granted("gho_token"), verdict)
        assertEquals(3, poller.calls)
    }

    @Test
    fun `a refusal is a verdict`() = runTest {
        // The other half of the rule: GitHub answering «нет» ends the flow at
        // once, because nothing later can change that answer.
        val poller = Poller(PollOutcome.Refused("access_denied"))

        val verdict = pollUntilDecided(
            interval = 5,
            expiresAt = Long.MAX_VALUE / 2,
            now = { 0L },
            poll = poller::poll,
        )

        assertEquals(PollVerdict.Refused("access_denied"), verdict)
        assertEquals(1, poller.calls)
    }

    @Test
    fun `slow down backs off and keeps polling`() = runTest {
        val poller = Poller(PollOutcome.SlowDown, PollOutcome.Granted("gho_token"))
        val started = testScheduler.currentTime

        val verdict = pollUntilDecided(
            interval = 5,
            expiresAt = Long.MAX_VALUE / 2,
            now = { 0L },
            poll = poller::poll,
        )

        assertEquals(PollVerdict.Granted("gho_token"), verdict)
        // Six seconds before the first ask — GitHub's minimum plus one — and
        // eleven before the second, because `slow_down` adds rather than
        // replaces.
        assertEquals(17_000L, testScheduler.currentTime - started)
    }

    @Test
    fun `an expired code stops the loop whatever the transport says`() = runTest {
        // The one thing an unreachable poll must not do is poll for ever. The
        // deadline is what bounds it, and it is read locally because a code
        // GitHub mis-stated the expiry of would otherwise be polled for the
        // rest of the process's life.
        val poller = Poller()

        val verdict = pollUntilDecided(
            interval = 5,
            expiresAt = 0L,
            now = { 10 * 60 * 1000L },
            poll = poller::poll,
        )

        assertEquals(PollVerdict.Refused("expired_token"), verdict)
        assertEquals("the deadline is checked before the request", 0, poller.calls)
    }

    @Test
    fun `an unreachable poll still ends at the deadline`() = runTest {
        // Every poll fails at the transport and the clock runs out. Without the
        // deadline this is the shape that would spin until the process died.
        var millis = 0L
        val poller = Poller()

        val verdict = pollUntilDecided(
            interval = 5,
            expiresAt = 60_000L,
            now = {
                millis += 20_000L
                millis
            },
            poll = poller::poll,
        )

        assertEquals(PollVerdict.Refused("expired_token"), verdict)
        assertTrue("it should have kept asking until the deadline", poller.calls >= 2)
    }

    @Test
    fun `a transport failure is the only failure it swallows`() = runTest {
        // A bug in the parsing of a response is not a network blip: it will
        // repeat on every poll for a quarter of an hour, and reporting it is
        // the only way anybody finds out. `IOException` is the line.
        val thrown = IllegalStateException("GitHub returned HTTP 500")
        var caught: Throwable? = null

        try {
            pollUntilDecided(
                interval = 5,
                expiresAt = Long.MAX_VALUE / 2,
                now = { 0L },
                poll = { throw thrown },
            )
        } catch (failure: IllegalStateException) {
            caught = failure
        }

        assertEquals(thrown, caught)
    }
}

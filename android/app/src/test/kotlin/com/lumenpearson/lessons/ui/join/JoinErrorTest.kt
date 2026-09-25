package com.lumenpearson.lessons.ui.join

import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.repository.JoinFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join screen's wording, over what the repository answered.
 *
 * `JoinFailureTest` in `:core:data` pins which status means which failure; this
 * pins that the screen keeps them apart afterwards. The pair that matters most
 * is the `403` and the `404`: both arrive as a code that did not work, and only
 * one of them is worth checking with the person who handed the code out. The
 * other has to point at the bot, which is the one place the person holding a
 * class code would never think to look. The `429` is the third: the code was
 * never looked at, and the answer is a length of time.
 */
class JoinErrorTest {

    @Test
    fun `an invite-only class is not a wrong code`() {
        assertEquals(JoinError.InviteOnly, JoinError.of(JoinFailure.InviteOnly))
    }

    @Test
    fun `an unknown code still is one`() {
        assertEquals(JoinError.UnknownCode, JoinError.of(JoinFailure.UnknownCode))
    }

    @Test
    fun `a throttled attempt is told in minutes, rounded up`() {
        // Rounded up, because «попробуйте через 15 минут» that is really 15:01
        // is one more refusal; and nobody waits 901 seconds, so the seconds the
        // header carries are not what goes on screen.
        assertEquals(
            JoinError.TooManyAttempts(minutes = 16),
            JoinError.of(JoinFailure.TooManyAttempts(retryAfterSeconds = 901)),
        )
        assertEquals(
            JoinError.TooManyAttempts(minutes = 15),
            JoinError.of(JoinFailure.TooManyAttempts(retryAfterSeconds = 900)),
        )
    }

    @Test
    fun `a wait shorter than a minute is still a minute, never zero`() {
        // «Подождите 0 минут» is an instruction to do nothing, next to a button
        // that will refuse again.
        assertEquals(
            JoinError.TooManyAttempts(minutes = 1),
            JoinError.of(JoinFailure.TooManyAttempts(retryAfterSeconds = 3)),
        )
    }

    @Test
    fun `a throttled attempt with no number is a wait without one`() {
        assertEquals(
            JoinError.TooManyAttempts(minutes = null),
            JoinError.of(JoinFailure.TooManyAttempts(retryAfterSeconds = null)),
        )
    }

    /**
     * Everything else keeps the server's own message under «Не удалось
     * подключиться: …», which is what this screen did for every failure before
     * the three above were split out.
     */
    @Test
    fun `anything else is still shown verbatim`() {
        val error = JoinError.of(JoinFailure.Offline(IOException("airplane mode")))

        assertTrue(error is JoinError.Rejected)
        assertEquals("airplane mode", (error as JoinError.Rejected).detail)
    }

    /**
     * #154: a blank address used to reach the screen as the interceptor's
     * English message under a Russian prefix — «Не удалось подключиться: No
     * server address configured» — on a screen that had just said a default
     * server was in use.
     */
    @Test
    fun `a missing server address is its own case, not an English message`() {
        val missing = JoinError.of(JoinFailure.Offline(ServerAddressMissingException()))
        assertEquals(JoinError.NoServer, missing)

        val wrapped = JoinError.of(
            JoinFailure.Offline(IOException("call failed", ServerAddressMissingException())),
        )
        assertEquals(JoinError.NoServer, wrapped)
    }
}

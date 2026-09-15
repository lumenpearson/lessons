package com.lumenpearson.lessons.ui.join

import com.lumenpearson.lessons.core.data.repository.JoinFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join screen's wording, over what the repository answered.
 *
 * `JoinFailureTest` in `:core:data` pins which status means which failure; this
 * pins that the screen keeps them apart afterwards. The pair that matters is
 * the `403` and the `404`: both arrive as a code that did not work, and only
 * one of them is worth checking with the person who handed the code out. The
 * other has to point at the bot, which is the one place the person holding a
 * class code would never think to look.
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

    /**
     * Everything else keeps the server's own message under «Не удалось
     * подключиться: …», which is what this screen did for every failure before
     * the two above were split out.
     */
    @Test
    fun `anything else is still shown verbatim`() {
        val error = JoinError.of(JoinFailure.Offline(IOException("airplane mode")))

        assertTrue(error is JoinError.Rejected)
        assertEquals("airplane mode", (error as JoinError.Rejected).detail)
    }
}

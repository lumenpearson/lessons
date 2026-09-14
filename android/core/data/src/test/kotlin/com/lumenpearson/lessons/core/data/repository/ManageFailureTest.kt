package com.lumenpearson.lessons.core.data.repository

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the management page is still the user's.
 *
 * `server/app/api/manage.py` sends three different `403`s and they do not mean
 * the same thing. Two of them — «привяжите телефон» and «нужна роль» — say the
 * page itself is gone, because the role is looked up per request from the
 * linked Telegram account and somebody has just changed it in the bot. The
 * third is one of the grant guards on approving a request, and it says only
 * that *this* button was pressed by somebody not senior enough to press it.
 *
 * Getting that wrong is silent in both directions: too eager and an
 * administrator is thrown off the page for trying to promote a peer, too lax
 * and a demoted one is left on a page where every tap fails with no
 * explanation.
 */
class ManageFailureTest {

    // -- the three 403s -----------------------------------------------------

    @Test
    fun `an unlinked device ends the session`() {
        val failure = ManageFailure.ofStatus(403, ManageFailure.DETAIL_NOT_LINKED)
        assertEquals(ManageFailure.NotLinked, failure)
        assertTrue(failure.endsTheSession)
    }

    @Test
    fun `a missing role ends the session and names the role`() {
        val failure = ManageFailure.ofStatus(403, "admin role required")
        assertEquals(ManageFailure.RoleLost("admin"), failure)
        assertTrue(failure.endsTheSession)
    }

    @Test
    fun `owner is named as readily as admin`() {
        assertEquals(ManageFailure.RoleLost("owner"), ManageFailure.ofStatus(403, "owner role required"))
    }

    /**
     * The grant guards. Both are answers about one button, and neither is a
     * reason to take the page away from an administrator who still has it.
     */
    @Test
    fun `a refused grant leaves the administrator where they are`() {
        val failure = ManageFailure.ofStatus(403, "cannot grant a role at or above your own")
        assertEquals(ManageFailure.NotAllowed("cannot grant a role at or above your own"), failure)
        assertFalse(failure.endsTheSession)
    }

    @Test
    fun `a refused role change leaves the administrator where they are`() {
        assertFalse(ManageFailure.ofStatus(403, "cannot change this member's role").endsTheSession)
    }

    /**
     * A 403 whose detail nobody recognises falls to the case that keeps the
     * user on the page. The safe direction: an unexplained refusal shown next
     * to the button that caused it is recoverable, and a page that vanishes is
     * not.
     */
    @Test
    fun `an unrecognised 403 does not end the session`() {
        val failure = ManageFailure.ofStatus(403, "something new the server learned to say")
        assertTrue(failure is ManageFailure.NotAllowed)
        assertFalse(failure.endsTheSession)
    }

    @Test
    fun `a 403 with no detail at all does not end the session`() {
        assertEquals(ManageFailure.NotAllowed(null), ManageFailure.ofStatus(403, null))
    }

    // -- everything else ----------------------------------------------------

    @Test
    fun `401 is the token itself being gone`() {
        val failure = ManageFailure.ofStatus(401)
        assertEquals(ManageFailure.SignedOut, failure)
        assertTrue(failure.endsTheSession)
    }

    @Test
    fun `404 is a row that is not this class's`() {
        assertEquals(ManageFailure.NotFound, ManageFailure.ofStatus(404, "Unknown subject"))
        assertFalse(ManageFailure.ofStatus(404).endsTheSession)
    }

    /** A 409 keeps its sentence: it is the only thing that tells three refusals apart. */
    @Test
    fun `409 carries the reason the class's own state refused`() {
        assertEquals(
            ManageFailure.Refused("this is the class default; make another one the default first"),
            ManageFailure.ofStatus(409, "this is the class default; make another one the default first"),
        )
    }

    @Test
    fun `422 is a value the server would not accept`() {
        assertEquals(ManageFailure.Invalid("unknown timezone"), ManageFailure.ofStatus(422, "unknown timezone"))
    }

    @Test
    fun `an unmapped status keeps its number`() {
        assertEquals(ManageFailure.Unexpected(500, null), ManageFailure.ofStatus(500))
    }

    @Test
    fun `no answer at all is being offline`() {
        val timeout = SocketTimeoutException("timed out")
        assertEquals(ManageFailure.Offline(timeout), ManageFailure.of(timeout))
        assertTrue(ManageFailure.of(IOException("no route")) is ManageFailure.Offline)
    }

    @Test
    fun `an already classified failure passes through untouched`() {
        assertEquals(ManageFailure.NotFound, ManageFailure.of(ManageFailure.NotFound))
    }

    @Test
    fun `anything else keeps its cause`() {
        val bug = IllegalStateException("boom")
        assertEquals(ManageFailure.Unexpected(null, bug), ManageFailure.of(bug))
    }

    // -- reading the detail out of the body ---------------------------------

    @Test
    fun `a raised HTTPException puts a string in detail`() {
        assertEquals("Unknown subject", ManageFailure.detailOf("""{"detail": "Unknown subject"}"""))
    }

    /** FastAPI's own validation failures are a list; the first `msg` is the readable one. */
    @Test
    fun `a validation failure puts a list in detail`() {
        val body = """{"detail": [{"loc": ["body", "name"], "msg": "must not be blank"}]}"""
        assertEquals("must not be blank", ManageFailure.detailOf(body))
    }

    @Test
    fun `an unreadable body is no detail rather than a guess`() {
        assertNull(ManageFailure.detailOf("<html><body>502 Bad Gateway</body></html>"))
        assertNull(ManageFailure.detailOf(""))
        assertNull(ManageFailure.detailOf(null))
        assertNull(ManageFailure.detailOf("""{"error": "nope"}"""))
    }

    // -- 503, which is not a failure of ours --------------------------------

    @Test
    fun `a 503 is the feature being off, and carries the server's own sentence`() {
        val failure = ManageFailure.ofStatus(503, "Поиск по школам не настроен — введите вручную")
        assertTrue(failure is ManageFailure.Unavailable)
        assertEquals(
            "Поиск по школам не настроен — введите вручную",
            (failure as ManageFailure.Unavailable).detail,
        )
        // Nothing about a switched-off directory says this phone lost its role.
        assertFalse(failure.endsTheSession)
    }

    @Test
    fun `a 503 with no detail still says something`() {
        val failure = ManageFailure.ofStatus(503) as ManageFailure.Unavailable
        assertNull(failure.detail)
        assertTrue(failure.message!!.isNotBlank())
    }
}

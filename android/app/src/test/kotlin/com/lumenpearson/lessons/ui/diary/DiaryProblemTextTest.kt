package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.ui.diary.DiaryProblemMessage.Counted
import com.lumenpearson.lessons.ui.diary.DiaryProblemMessage.Line
import com.lumenpearson.lessons.ui.diary.DiaryProblemMessage.WithUpstream
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which sentence each way of not getting into the diary gets.
 *
 * The screen half of #153: the data layer had learnt to tell a throttle, a
 * diary switched off on the server and a server the diary refuses from «не
 * отвечает», and the screen still said «Дневник не отвечает. Попробуйте позже.»
 * for all three, because the bridge it went through had no member for them. The
 * one mapping is now `diaryProblemMessage`, and these are its cases.
 */
class DiaryProblemTextTest {

    private val host = "asurso.ru"

    @Test
    fun `too many attempts reads as too many attempts, with the wait when there is one`() {
        assertEquals(Line(R.string.diary_problem_too_many), diaryProblemMessage(DiarySignInProblem.TooManyAttempts(null)))
        assertEquals(
            Counted(R.plurals.diary_problem_too_many_wait, 2),
            diaryProblemMessage(DiarySignInProblem.TooManyAttempts(retryAfterSeconds = 61)),
        )
        // Forty seconds is «через 1 минуту», never «через 0 минут».
        assertEquals(
            Counted(R.plurals.diary_problem_too_many_wait, 1),
            diaryProblemMessage(DiarySignInProblem.TooManyAttempts(retryAfterSeconds = 40)),
        )
    }

    @Test
    fun `a diary switched off on the server reads as switched off`() {
        assertEquals(Line(R.string.diary_problem_server_disabled), diaryProblemMessage(DiarySignInProblem.ServerDisabled))
    }

    @Test
    fun `a refused server address reads as the diary refusing our server`() {
        assertEquals(
            Line(R.string.diary_problem_server_address_refused),
            diaryProblemMessage(DiarySignInProblem.ServerAddressRefused),
        )
    }

    @Test
    fun `a timeout reads as a timeout, naming the diary when it was the diary`() {
        assertEquals(Line(R.string.diary_problem_host_timeout, listOf(host)), diaryProblemMessage(DiarySignInProblem.Timeout(host)))
        assertEquals(Line(R.string.diary_problem_timeout), diaryProblemMessage(DiarySignInProblem.Timeout(null)))
    }

    @Test
    fun `every problem that carries the diary's host says it`() {
        val hosted = listOf(
            DiarySignInProblem.ProviderUnavailable(host),
            DiarySignInProblem.ProviderRefusesPhone(host),
            DiarySignInProblem.ProviderUntrusted(host),
            DiarySignInProblem.ProviderUnreadable(host),
            DiarySignInProblem.ProviderOffline(host),
            DiarySignInProblem.Timeout(host),
        )
        for (problem in hosted) {
            val message = diaryProblemMessage(problem)
            assertTrue("$problem names no host: $message", message is Line && message.args == listOf(host))
        }
    }

    /**
     * Every member, and no two sharing a sentence unless they mean the same
     * thing to the person reading. A new member fails to compile in the
     * mapping; this is what says its sentence is its own.
     */
    @Test
    fun `every problem has a sentence of its own`() {
        val all = listOf(
            DiarySignInProblem.WrongPassword(null),
            DiarySignInProblem.GosuslugiOnly(null),
            DiarySignInProblem.ProviderUnavailable(null),
            DiarySignInProblem.ProviderRefusesPhone(null),
            DiarySignInProblem.ProviderUntrusted(null),
            DiarySignInProblem.ProviderUnreadable(null),
            DiarySignInProblem.ProviderOffline(null),
            DiarySignInProblem.Timeout(null),
            DiarySignInProblem.Offline,
            DiarySignInProblem.RegisterUnreachable,
            DiarySignInProblem.ServerMissing,
            DiarySignInProblem.ServerTooOld,
            DiarySignInProblem.ServerDisabled,
            DiarySignInProblem.RegionNotServed,
            DiarySignInProblem.ServerRefusedSession,
            DiarySignInProblem.ServerAddressRefused,
            DiarySignInProblem.NoStudent,
            DiarySignInProblem.TooManyAttempts(null),
            DiarySignInProblem.SessionAgedOut,
            DiarySignInProblem.LoginTooShort,
            DiarySignInProblem.ReauthRequired,
            DiarySignInProblem.SignInRequired,
        )
        val ids = all.map { (diaryProblemMessage(it) as Line).id }
        assertEquals("two problems share a sentence: $all", ids.size, ids.toSet().size)
    }

    @Test
    fun `what the diary said about a refused password is shown beside it`() {
        assertEquals(Line(R.string.diary_problem_wrong_password), diaryProblemMessage(DiarySignInProblem.WrongPassword(null)))
        assertEquals(
            WithUpstream(Line(R.string.diary_problem_wrong_password), "Учётная запись заблокирована"),
            diaryProblemMessage(DiarySignInProblem.WrongPassword("  Учётная запись заблокирована ")),
        )
    }

    /**
     * The read side: a failed read of a week goes through the same mapping, so
     * the failure card under a week says what the sign-in form would.
     */
    @Test
    fun `a failed read is the problem it means`() {
        assertEquals(DiarySignInProblem.TooManyAttempts(30), DiaryFailure.Throttled(30).asProblem())
        assertEquals(DiarySignInProblem.ServerDisabled, DiaryFailure.Disabled.asProblem())
        assertEquals(DiarySignInProblem.ServerAddressRefused, DiaryFailure.ServerAddressRefused.asProblem())
        assertEquals(DiarySignInProblem.Timeout(null), DiaryFailure.Offline(SocketTimeoutException("slow")).asProblem())
        assertEquals(DiarySignInProblem.Offline, DiaryFailure.Offline(IOException("no route")).asProblem())
        // What this app asked wrongly keeps its own sentence.
        assertNull(DiaryFailure.BadRange.asProblem())
        assertNull(DiaryFailure.Rejected.asProblem())
        assertNull(DiaryFailure.UnknownStudent.asProblem())
    }

    @Test
    fun `retry is offered only where the data layer says it can help`() {
        assertTrue(DiarySignInProblem.Offline.offersRetry)
        assertTrue(DiarySignInProblem.Timeout(host).offersRetry)
        assertFalse(DiarySignInProblem.ServerDisabled.offersRetry)
        assertFalse(DiarySignInProblem.ServerAddressRefused.offersRetry)
        assertFalse(DiarySignInProblem.TooManyAttempts(60).offersRetry)
    }
}

package com.lumenpearson.lessons.core.data.repository

import java.io.IOException
import retrofit2.HttpException

/**
 * Why `POST /api/v1/join` did not let this phone in.
 *
 * The join screen used to render the raw exception, and that was honest while
 * every refusal meant the same thing — «код не подошёл, вот что сказал
 * сервер». It stopped being honest when a class gained a second way to refuse a
 * code that is perfectly real: a class in [ClassJoinMode.INVITE] answers `403`
 * to its own join code, and reporting that as a bad code sends somebody back to
 * whoever read the code out to them instead of to the bot, which is the only
 * place that can actually help.
 *
 * So the codes are classified here rather than on the screen, for the same
 * reason [ManageFailure] is: `retrofit2.HttpException` is an implementation
 * detail of this module and reaches no caller above it, so the screen could not
 * tell a `403` from a `404` even if it wanted to.
 *
 * Every case keeps the message the exception it came from carried, so the
 * failures that are still just "here is what the server said" read exactly as
 * they did before.
 */
sealed class JoinFailure(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * `403`: a real code for a real class that admits phones only on a personal
     * invite from the bot.
     *
     * Its own case and not a [Rejected] because it is the one refusal with an
     * instruction in it, and the instruction is not "check the code".
     */
    data object InviteOnly : JoinFailure("This class admits phones by personal invite only")

    /** `404`: no class and no live invite answers to this code. */
    data object UnknownCode : JoinFailure("No class or invite answers to this code")

    /**
     * `429`: the throttle has stopped counting and started refusing.
     *
     * [retryAfterSeconds] comes from the `Retry-After` header the server sends
     * with it, and it is the whole reason this is not a [Rejected]: every other
     * refusal on this screen is answered by doing something to the code, and
     * this one is answered by waiting a knowable length of time. Rendered as
     * «HTTP 429 Too Many Requests», which is what happened before, it is an
     * English sentence on a Russian screen that tells somebody to check a code
     * that was probably right.
     *
     * Null when the header is missing or unreadable — a proxy may strip it —
     * and the screen then says to wait without saying how long, which is still
     * the truth.
     */
    data class TooManyAttempts(val retryAfterSeconds: Int?) :
        JoinFailure("Too many join attempts")

    /** No answer at all: no network, a wrong address, a dead server. */
    data class Offline(val reason: Throwable) :
        JoinFailure(reason.message ?: "Could not reach the server", reason)

    /**
     * Anything else the server said, kept verbatim.
     *
     * Every `5xx` lands here. The screen shows [message] after «Не удалось
     * подключиться:», which is what it did for all of these before this type
     * existed — an English tail on a Russian line, and the right trade for a
     * failure nobody can act on except by reporting it.
     */
    data class Rejected(val code: Int?, val reason: Throwable?) :
        JoinFailure(reason?.message ?: "The server refused this code", reason)

    companion object {

        /** Classifies whatever the join call threw. */
        fun of(failure: Throwable): JoinFailure = when (failure) {
            is JoinFailure -> failure
            is HttpException -> ofStatus(failure.code(), failure)
            is IOException -> Offline(failure)
            else -> Rejected(code = null, reason = failure)
        }

        /**
         * The rule, over a status alone.
         *
         * None of the three named codes needs the server's `detail` to be read:
         * `POST /join` raises exactly one `403`, one `404` and one `429`, so
         * the status is the whole answer, and a message we would have to match
         * on is a Russian sentence somebody will reword.
         */
        fun ofStatus(code: Int, reason: Throwable? = null): JoinFailure = when (code) {
            403 -> InviteOnly
            404 -> UnknownCode
            429 -> TooManyAttempts(retryAfterSeconds = retryAfterOf(reason))
            else -> Rejected(code = code, reason = reason)
        }

        /**
         * `Retry-After` off the response, in seconds, when it is there and is a
         * number.
         *
         * Only the delta-seconds form is read. The HTTP date form is legal and
         * this server never sends it, and parsing a date against a phone clock
         * that may be wrong would turn a missing header — which the screen
         * already handles — into a confidently wrong number of minutes.
         */
        private fun retryAfterOf(reason: Throwable?): Int? = (reason as? HttpException)
            ?.response()
            ?.headers()
            ?.get("Retry-After")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }
}

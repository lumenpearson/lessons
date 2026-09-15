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

    /** No answer at all: no network, a wrong address, a dead server. */
    data class Offline(val reason: Throwable) :
        JoinFailure(reason.message ?: "Could not reach the server", reason)

    /**
     * Anything else the server said, kept verbatim.
     *
     * The rate limiter's `429` lands here, as does every `5xx`. The screen
     * shows [message] after «Не удалось подключиться:», which is what it did
     * for all of these before this type existed.
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
         * Neither of the two named codes needs the server's `detail` to be read:
         * `POST /join` raises exactly one `403` and exactly one `404`, so the
         * status is the whole answer, and a message we would have to match on
         * is a Russian sentence somebody will reword.
         */
        fun ofStatus(code: Int, reason: Throwable? = null): JoinFailure = when (code) {
            403 -> InviteOnly
            404 -> UnknownCode
            else -> Rejected(code = code, reason = reason)
        }
    }
}

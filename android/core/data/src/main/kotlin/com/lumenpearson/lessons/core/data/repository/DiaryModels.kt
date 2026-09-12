package com.lumenpearson.lessons.core.data.repository

import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import retrofit2.HttpException

/**
 * The diary's value types, as the app above this line sees them.
 *
 * Nothing here carries a wire field, a date string or an upstream code: the
 * whole point of the server's `/api/v1/diary` surface is that the client never
 * learns what `p_educations[]` or `estimate_type_code` are, and the whole point
 * of these types is that the screens never learn what JSON is.
 */

/**
 * A signed-in diary session.
 *
 * [token] is this server's own bearer, not the upstream's, and it is separate
 * from the class device token in [Session] — a phone may hold either, both or
 * neither. [login] is kept only so the re-authentication prompt can say whose
 * password it is asking for; the password itself is stored nowhere, here or on
 * the server.
 */
data class DiarySession(
    val login: String,
    val token: String,
)

/**
 * One pupil the account may see.
 *
 * [id] is the server's handle for the child and is checked against the account
 * on every request, so it is safe to keep on the device and useless off it.
 */
data class DiaryStudent(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val middleName: String?,
    val fullName: String,
    val school: String?,
    val className: String?,
) {
    /** "Иванов Иван" — enough to tell two children apart in a picker. */
    val shortName: String
        get() = listOf(lastName, firstName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { fullName }
}

/**
 * One lesson of one day.
 *
 * [startsAt] and [endsAt] are nullable because the diary publishes timetables
 * without bell times often enough that dropping those lessons would be lying
 * about the day. [homework] is the text the teacher attached to this lesson;
 * the same text also arrives through the homework endpoint, which is where the
 * week view reads it from.
 */
data class DiaryLesson(
    val date: LocalDate,
    val number: Int?,
    val subject: String,
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val room: String?,
    val teacher: String?,
    val homework: String?,
    val topic: String?,
)

/** Homework, by the day it is due. */
data class DiaryHomework(
    val id: Long?,
    val dueDate: LocalDate,
    val subject: String,
    val text: String,
    val teacher: String?,
)

/**
 * What a cell of the register means.
 *
 * The diary puts marks, absences, lateness and remarks in one list and tells
 * them apart by a numeric code; the server translates that code into this, so
 * the app can draw an absence as an absence instead of as a grade of "Н".
 */
enum class DiaryMarkKind {
    /** A real mark. Only these count towards an average. */
    GRADE,
    ABSENCE,
    LATE,
    REMARK,
    OTHER,
    ;

    companion object {
        /**
         * Anything unrecognised becomes [OTHER] rather than failing: a diary
         * that grows a sixth kind must not stop an older build from reading
         * the five it knows.
         */
        fun fromWire(raw: String?): DiaryMarkKind {
            val name = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == name } ?: OTHER
        }
    }
}

/**
 * One entry of the register.
 *
 * [value] is what is written in the cell — a digit for a mark, "Н" for an
 * absence — and [kind] is what it means. A UI that reads only [value] would
 * average absences into the marks, which is the mistake this pair exists to
 * prevent.
 */
data class DiaryMark(
    val id: Long?,
    val subjectId: Long?,
    val subject: String,
    val date: LocalDate?,
    val value: String,
    val kind: DiaryMarkKind,
    val reason: String?,
    val comment: String?,
) {
    /**
     * The mark as a number, or `null` when it is not one.
     *
     * Only a [DiaryMarkKind.GRADE] can be numeric: "5" written into an absence
     * row would be a code, not a five. Values like "5-" and "4/5" are left out
     * on purpose — half the school writes them and no two mean the same thing,
     * so counting them would invent precision the diary does not have.
     */
    val numericValue: Int?
        get() = if (kind == DiaryMarkKind.GRADE) value.trim().toIntOrNull() else null
}

/** A quarter or a trimester, as the school divides its year. */
data class DiaryPeriod(
    val id: Long,
    val name: String,
    val startsOn: LocalDate?,
    val endsOn: LocalDate?,
    val isCurrent: Boolean,
)

/**
 * Why a diary call did not work, as something the UI can switch on.
 *
 * A sealed hierarchy rather than a message, because the six cases lead to six
 * different screens and telling them apart by parsing text is how an app ends
 * up showing "попробуйте позже" to somebody whose password has simply expired.
 * The mapping is the table in `docs/api.md`, "Коды ошибок".
 *
 * It extends [Exception] so that it can travel inside the `Result` every
 * repository method returns, like the raw exceptions the join screen already
 * receives.
 */
sealed class DiaryFailure(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * `401` without the re-auth header: our own session token is not good any
     * more — signed out elsewhere, or never signed in. The app asks for login
     * *and* password.
     *
     * On the sign-in call itself this is the other 401 the server can send:
     * credentials the diary refused. Same screen either way, which is why it is
     * one case.
     */
    data object SignInRequired : DiaryFailure("Diary sign-in required")

    /**
     * `401` with `X-Diary-Reauth: required`: our session is fine, the upstream
     * one died. The login is still known, so the app asks only for the
     * password — a different screen from [SignInRequired], which is the whole
     * reason the server sends a header instead of one 401 for both.
     */
    data object ReauthRequired : DiaryFailure("Diary session expired upstream")

    /** `404`: an id this account may not see. Never shown as "not found". */
    data object UnknownStudent : DiaryFailure("Unknown student for this account")

    /** `422`: the range was inverted or wider than 62 days. A bug on our side. */
    data object BadRange : DiaryFailure("Date range rejected by the server")

    /** `502`: the diary answered something the server could not read. */
    data object Unreadable : DiaryFailure("The diary answered in an unreadable way")

    /** `503`: the diary is down. Worth retrying, and only that. */
    data object Unavailable : DiaryFailure("The diary is not answering")

    /** No answer at all: no network, wrong address, a timeout. */
    data class Offline(val reason: Throwable) :
        DiaryFailure("Could not reach the server", reason)

    /** Anything else, kept with its cause so a bug report can carry it. */
    data class Unexpected(val code: Int?, val reason: Throwable) :
        DiaryFailure("Unexpected diary failure (${code ?: "no status"})", reason)

    companion object {

        /** The header that separates "sign in" from "type your password again". */
        const val REAUTH_HEADER = "X-Diary-Reauth"

        /**
         * Classifies whatever a call threw.
         *
         * Kept here rather than in the repository so that the rule has one
         * home and one test: every screen in the app depends on 401-with-header
         * being a different thing from 401, and that distinction lives in
         * exactly these six lines.
         */
        fun of(failure: Throwable): DiaryFailure = when (failure) {
            is DiaryFailure -> failure
            is HttpException -> ofHttp(failure)
            is IOException -> Offline(failure)
            else -> Unexpected(code = null, reason = failure)
        }

        private fun ofHttp(failure: HttpException): DiaryFailure = when (failure.code()) {
            401 -> {
                // `?.headers()` and not the message: the header is the signal,
                // and a proxy that rewrites the body still has to carry it.
                val reauth = failure.response()
                    ?.headers()
                    ?.get(REAUTH_HEADER)
                    ?.trim()
                    ?.equals("required", ignoreCase = true) == true
                if (reauth) ReauthRequired else SignInRequired
            }

            404 -> UnknownStudent
            422 -> BadRange
            502 -> Unreadable
            503 -> Unavailable
            else -> Unexpected(code = failure.code(), reason = failure)
        }
    }
}

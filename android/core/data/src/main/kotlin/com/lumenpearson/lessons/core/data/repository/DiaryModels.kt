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
 * A field of a lesson or of a homework item that a family may correct.
 *
 * An enum rather than the strings the wire uses, because the server refuses
 * anything outside this set with a `422` and a screen that spelled "techer"
 * would find that out from a user. The wire names are the server's
 * `LESSON_FIELDS` and `HOMEWORK_FIELDS` in `services/diary_overrides.py`.
 *
 * A subject is deliberately not here: it is half of the key a correction is
 * filed under, so renaming it would move the correction onto a different lesson
 * or onto nothing. Marks and attendance are not here either, and for a
 * different reason — they are claims about what happened, and an app that let a
 * child hide a two from a parent would be producing a false record that looks
 * official.
 */
enum class DiaryField(private val wireName: String) {
    /** A lesson's homework, as the teacher attached it to that lesson. */
    HOMEWORK("homework"),
    ROOM("room"),
    TEACHER("teacher"),
    TOPIC("topic"),

    /** The body of a homework item — the only correctable field it has. */
    TEXT("text"),
    ;

    /** What the server calls this field. The only place these strings are written. */
    fun wire(): String = wireName

    companion object {
        /**
         * `null` for anything this build does not know, which is not the same
         * decision as [DiaryMarkKind.fromWire]'s "other": a mark of an unknown
         * kind can still be shown, whereas a correction of an unknown field has
         * nothing to be drawn next to and no reset button that would find it.
         */
        fun fromWire(raw: String?): DiaryField? {
            val name = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName == name }
        }
    }
}

/**
 * One correction, as it is shown over the value it replaces.
 *
 * [field] is kept as the server spelled it rather than as a [DiaryField], so
 * that this layer neither drops nor guesses at a field a newer server knows.
 * What the screen does with an unrecognised one is the screen's decision, and
 * it drops it: `DiaryPresentation` marks a row «Исправлено» only for
 * corrections it can also offer a reset for, because a badge over a reset
 * button that finds nothing is worse than no badge. The cost is that a value
 * corrected through a field this build does not know is drawn as if nobody had
 * touched it — the trade is argued where it is made, on
 * `DiaryCorrections.hasCorrections`.
 *
 * [original] is what the diary says **now** — the client renders it as
 * «в дневнике: …» — and [changedUpstream] says the diary has moved since the
 * correction was written, so the value being covered up is no longer the one
 * the person decided to replace. It is not reset automatically: they typed it,
 * and throwing it away because a teacher edited a field is not our decision.
 */
data class DiaryEdit(
    val field: String,
    val value: String,
    val original: String?,
    val changedUpstream: Boolean,
)

/**
 * One lesson of one day.
 *
 * [startsAt] and [endsAt] are nullable because the diary publishes timetables
 * without bell times often enough that dropping those lessons would be lying
 * about the day. [homework] is the text the teacher attached to this lesson;
 * the same text also arrives through the homework endpoint, which is where the
 * week view reads it from.
 *
 * The last three carry the corrections. Every field above them is already
 * corrected — the server lays the values over on the way out — so a screen that
 * only draws lessons needs none of this; [edits] is what a screen that offers
 * «сбросить» needs, and [target] is what it sends back to do it.
 *
 * They default so that every construction site that predates corrections keeps
 * compiling, and because those defaults are also the honest answer: no target,
 * nothing corrected, nothing ambiguous.
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
    /** Echoed back verbatim when a correction is written; never built here. */
    val target: String = "",
    val edits: List<DiaryEdit> = emptyList(),
    /**
     * Another lesson the same day shares this key — two groups of one split
     * class, most often — so no correction is applied to either and the screen
     * says so. Putting one group's room on both halves would be worse.
     */
    val ambiguous: Boolean = false,
)

/** Homework, by the day it is due. @see DiaryLesson for [target] and [edits]. */
data class DiaryHomework(
    val id: Long?,
    val dueDate: LocalDate,
    val subject: String,
    val text: String,
    val teacher: String?,
    val target: String = "",
    val edits: List<DiaryEdit> = emptyList(),
    /**
     * Two assignments in one subject due the same day, neither carrying an
     * upstream id, share a key — so no correction is applied to either and the
     * screen says so. @see DiaryLesson.ambiguous
     */
    val ambiguous: Boolean = false,
)

/**
 * One stored correction, as `/overrides` lists them.
 *
 * Unlike [DiaryEdit] this names a [DiaryField]: the list is the screen that
 * resets things, and a row it could not name a field for is a row whose reset
 * button would go nowhere. Such a row is dropped in the mapper.
 *
 * [target] is the server's key and is kept byte for byte — it is composed from
 * a date, a lesson number and a subject name, so a trailing space in it belongs
 * to the key and not to the formatting.
 *
 * [originalWhenWritten] is what the diary said at the moment the correction was
 * made. [DiaryEdit.original] is the other of the two and means the opposite —
 * what the diary says **now** — and only that one may be drawn as
 * «в дневнике: …»; this one dates a correction, it does not describe the
 * current state of anything.
 */
data class DiaryOverrideRecord(
    val target: String,
    val field: DiaryField,
    val value: String,
    val originalWhenWritten: String?,
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
 * A sealed hierarchy rather than a message, because each case leads to a
 * different screen and telling them apart by parsing text is how an app ends
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

    /**
     * `422` on a correction: the server will not file one there.
     *
     * Either the target names nothing correctable — which can only happen to a
     * client that built a key instead of echoing one — or the field is outside
     * the closed set in [DiaryField]. Both are ours to fix, so the screen says
     * «это нельзя исправить» and does not offer a retry.
     */
    data object Rejected : DiaryFailure("The server refused this correction")

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
         * exactly these few lines.
         *
         * @param unprocessable what a `422` means on the call that threw. It is
         *   the one status this API uses for two things — a refused date range
         *   on the reads, a refused correction on `/overrides` — and nothing in
         *   the response separates them except a Russian sentence in `detail`,
         *   which is exactly the text-parsing this class exists to avoid. So
         *   the caller, which knows what it asked for, says which one it is.
         */
        fun of(
            failure: Throwable,
            unprocessable: DiaryFailure = BadRange,
        ): DiaryFailure = when (failure) {
            is DiaryFailure -> failure
            is HttpException -> ofHttp(failure, unprocessable)
            is IOException -> Offline(failure)
            else -> Unexpected(code = null, reason = failure)
        }

        private fun ofHttp(
            failure: HttpException,
            unprocessable: DiaryFailure,
        ): DiaryFailure = when (failure.code()) {
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
            422 -> unprocessable
            502 -> Unreadable
            503 -> Unavailable
            else -> Unexpected(code = failure.code(), reason = failure)
        }
    }
}

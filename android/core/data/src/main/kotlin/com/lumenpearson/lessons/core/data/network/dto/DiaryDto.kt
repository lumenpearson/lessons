package com.lumenpearson.lessons.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shapes of `/api/v1/diary`, mirroring every `Diary*` model in
 * `server/app/schemas.py`.
 *
 * Same rules as [BundleDto]: an explicit `@SerialName` on every field so a
 * rename on either side of the repository is a test failure rather than a
 * silently missing value, defaults everywhere a field may be absent, and dates
 * and times left as [String] so that one unreadable value costs one row in the
 * mappers instead of the whole response.
 *
 * Nothing here is an enum. `kind` arrives as a free-form string and is resolved
 * through `DiaryMarkKind.fromWire`, because a diary that learns a sixth kind of
 * register entry must not stop an older build from showing the other five.
 */

/** `POST /login` — used once and never stored; see [DiaryLoginResponseDto]. */
@Serializable
internal data class DiaryLoginRequestDto(
    @SerialName("login") val login: String,
    @SerialName("password") val password: String,
)

/**
 * Mirrors `DiaryLoginOut`.
 *
 * The password is deliberately absent from the answer: the server keeps only
 * the session it opened upstream, which is the whole reason a dead session
 * comes back as `401` + `X-Diary-Reauth: required` rather than being refreshed.
 */
@Serializable
internal data class DiaryLoginResponseDto(
    @SerialName("token") val token: String,
    @SerialName("login") val login: String = "",
)

/** Mirrors `DiaryStudentOut`: one child this account may see. */
@Serializable
internal data class DiaryStudentDto(
    @SerialName("id") val id: Long,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("middle_name") val middleName: String? = null,
    @SerialName("full_name") val fullName: String = "",
    @SerialName("school") val school: String? = null,
    @SerialName("class_name") val className: String? = null,
)

/**
 * Mirrors `DiaryEditOut`: one field a family has laid a correction over.
 *
 * [original] is what the diary says *now*, not what it said when the correction
 * was written, and [changedUpstream] is how the two are told apart — the server
 * compares them, because only it kept the value the person was looking at.
 */
@Serializable
internal data class DiaryEditDto(
    @SerialName("field") val field: String = "",
    @SerialName("value") val value: String = "",
    @SerialName("original") val original: String? = null,
    @SerialName("changed_upstream") val changedUpstream: Boolean = false,
)

/**
 * Mirrors `DiaryLessonOut`.
 *
 * [number], [startsAt] and [endsAt] are nullable on the server, so a lesson the
 * upstream published without a bell time still decodes and is still shown —
 * without a time, which is what the diary actually knows about it.
 *
 * [target] is the key a correction for this lesson is filed under. It is read
 * and echoed, never built here: the server composes it from the day, the lesson
 * number and the subject, and a second implementation of a string that has to
 * match exactly would agree until the first lesson without a number.
 */
@Serializable
internal data class DiaryLessonDto(
    @SerialName("date") val date: String,
    @SerialName("number") val number: Int? = null,
    @SerialName("subject") val subject: String = "",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("room") val room: String? = null,
    @SerialName("teacher") val teacher: String? = null,
    @SerialName("homework") val homework: String? = null,
    @SerialName("topic") val topic: String? = null,
    @SerialName("target") val target: String = "",
    @SerialName("edits") val edits: List<DiaryEditDto> = emptyList(),
    // False is also what a server too old to know about corrections means by
    // not sending it, which is the right reading: no correction, no ambiguity.
    @SerialName("ambiguous") val ambiguous: Boolean = false,
)

/** Mirrors `DiaryHomeworkOut`: upstream a field of a lesson, here its own row. */
@Serializable
internal data class DiaryHomeworkDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("due_date") val dueDate: String,
    @SerialName("subject") val subject: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("teacher") val teacher: String? = null,
    /** @see DiaryLessonDto.target — homework is keyed by its upstream id when it has one. */
    @SerialName("target") val target: String = "",
    @SerialName("edits") val edits: List<DiaryEditDto> = emptyList(),
    // Two assignments in one subject due the same day, neither carrying an
    // upstream id, share a key. False is also what a server too old to know
    // about corrections means by not sending it, as on DiaryLessonDto.
    @SerialName("ambiguous") val ambiguous: Boolean = false,
)

/**
 * Mirrors `DiaryOverrideOut`: one stored correction, as `/overrides` lists them.
 *
 * [updatedAt] is decoded and goes no further than this file. The list exists so
 * a family can see and reset what it has corrected, and "исправлено 14 сентября"
 * is a date nothing in the app has asked for yet; mirroring the field anyway
 * keeps this a copy of the server model rather than an edited one.
 */
@Serializable
internal data class DiaryOverrideDto(
    @SerialName("target") val target: String = "",
    @SerialName("field") val field: String = "",
    @SerialName("value") val value: String = "",
    /**
     * What the diary said **when this correction was written**, which is the
     * opposite of what [DiaryEditDto.original] carries: that one is the value
     * currently being covered up. The server spells the two apart because a
     * client holding one name for both renders «в дневнике: …» from whichever
     * of them it happened to have.
     */
    @SerialName("original_when_written") val originalWhenWritten: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
)

/**
 * Mirrors `DiaryOverrideIn`: the body of `PUT /overrides`.
 *
 * The three required fields carry no default, unlike every decoded shape above.
 * A default here would not protect an older server from anything — this one is
 * only ever encoded — and it would let a correction be sent with an empty
 * target, which is the one value in this feature that must survive the round
 * trip byte for byte.
 */
@Serializable
internal data class DiaryOverrideRequestDto(
    @SerialName("target") val target: String,
    @SerialName("field") val field: String,
    @SerialName("value") val value: String,
    @SerialName("original") val original: String? = null,
)

/**
 * Mirrors `DiaryResetIn`: the body of `POST /overrides/reset`.
 *
 * A body rather than a query string, and the reason is the target: it is free
 * text, so a subject named «Физика & астрономия» composes a key with an
 * ampersand in it, and a caller that percent-encodes it a shade differently
 * from the server matches no row while being answered 204.
 *
 * No defaults, for the reason [DiaryOverrideRequestDto] has none.
 */
@Serializable
internal data class DiaryResetRequestDto(
    @SerialName("target") val target: String,
    @SerialName("field") val field: String,
)

/**
 * Mirrors `DiaryMarkOut`: one cell of the register.
 *
 * [value] is what is written in the cell and [kind] says what it means, so an
 * absence can be drawn as an absence without the client knowing that upstream
 * it was type code 30000.
 */
@Serializable
internal data class DiaryMarkDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject") val subject: String = "",
    @SerialName("date") val date: String? = null,
    @SerialName("value") val value: String = "",
    @SerialName("kind") val kind: String = "",
    @SerialName("reason") val reason: String? = null,
    @SerialName("comment") val comment: String? = null,
)

/** Mirrors `DiaryPeriodOut`: a quarter or a trimester, as the school names it. */
@Serializable
internal data class DiaryPeriodDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String = "",
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("ends_on") val endsOn: String? = null,
    @SerialName("is_current") val isCurrent: Boolean = false,
)

/** Mirrors `DiarySubjectOut`. */
@Serializable
internal data class DiarySubjectDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String = "",
)

/** Mirrors `DiaryTeacherOut`. */
@Serializable
internal data class DiaryTeacherDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String = "",
    @SerialName("position") val position: String? = null,
    @SerialName("subjects") val subjects: List<String> = emptyList(),
)

/** Mirrors `DiaryAttendanceOut`: one pass through the turnstile. */
@Serializable
internal data class DiaryAttendanceDto(
    @SerialName("at") val at: String,
    @SerialName("direction") val direction: String = "",
)

package com.lumenpearson.lessons.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

/**
 * `POST /session`: a session the phone opened with the diary itself, handed
 * over so our server can keep reading with it. Mirrors `DiarySessionBody`.
 *
 * There is no password field, and there is nowhere to put one: the server
 * refuses any key it does not know with a `422` (`extra="forbid"`), so a
 * password could not be sent here even by mistake. The app stopped calling
 * `POST /login`, which is the only endpoint that ever took one.
 *
 * [credential] is the provider's own shape — [PetersburgCredentialDto] or
 * [NetSchoolCredentialDto] as JSON — because the server tells the two bodies
 * apart by [provider] and validates each half separately. [region] and
 * [schoolId] are left out of the JSON when `null` (the app's `Json` drops
 * nulls): Petersburg's half of the union has no such fields, and a `null`
 * there would be a key the server forbids.
 *
 * A plain class with a redacted [toString], not a data class: a data class
 * prints every field, and one of these is a live session.
 */
@Serializable
internal class DiarySessionRequestDto(
    @SerialName("provider") val provider: String,
    @SerialName("login") val login: String,
    @SerialName("region") val region: String? = null,
    @SerialName("school_id") val schoolId: Long? = null,
    @SerialName("credential") val credential: JsonObject,
) {
    override fun toString(): String = "DiarySessionRequestDto($provider, $region, <redacted>)"
}

/** Petersburg's half of [DiarySessionRequestDto.credential]: its `X-JWT-Token`. */
@Serializable
internal class PetersburgCredentialDto(
    @SerialName("token") val token: String,
)

/**
 * «Сетевой город»'s half: the `at` bearer, the two session cookies, and the
 * two fields the server's own sign-in keeps beside them. `null`s are dropped on
 * the way out, which is what the server's `exclude_none` stores anyway.
 */
@Serializable
internal class NetSchoolCredentialDto(
    @SerialName("at") val at: String,
    @SerialName("cookies") val cookies: NetSchoolCookiesDto,
    @SerialName("ver") val ver: String? = null,
    @SerialName("time_out") val timeOut: Long? = null,
)

/**
 * The only two cookie names the server takes; any other is a `422`, because
 * it would be sent upstream on every read.
 */
@Serializable
internal class NetSchoolCookiesDto(
    @SerialName("NSSESSIONID") val session: String,
    @SerialName("ESRNSec") val security: String? = null,
)

/**
 * Mirrors `DiarySessionOut`: our bearer for the registered session, and what
 * the phone needs to keep reading it — never the upstream credential, which
 * stays sealed on the server.
 *
 * [zone] is the zone the server cuts this diary's days at, so the phone's
 * «today» is the server's. [students] saves the import one round trip: the
 * validating read already fetched them.
 */
@Serializable
internal data class DiarySessionResponseDto(
    @SerialName("token") val token: String,
    @SerialName("login") val login: String = "",
    @SerialName("provider") val provider: String = "",
    @SerialName("region") val region: String? = null,
    @SerialName("school_id") val schoolId: Long? = null,
    @SerialName("school_name") val schoolName: String? = null,
    @SerialName("zone") val zone: String? = null,
    @SerialName("students") val students: List<DiaryStudentDto> = emptyList(),
) {
    override fun toString(): String = "DiarySessionResponseDto($provider, $region, <redacted>)"
}

/**
 * Mirrors `DiaryCapabilitiesOut`: asked before a password is taken, so a
 * server that cannot keep the session is found out before anything is sent to
 * a diary. A server from before registration answers `404` instead.
 */
@Serializable
internal data class DiaryCapabilitiesDto(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("registration") val registration: Boolean = false,
    @SerialName("providers") val providers: DiaryProvidersDto = DiaryProvidersDto(),
)

/**
 * Mirrors `DiaryProvidersOut`. [petersburg] is an empty object today; its
 * presence is what says the server serves Petersburg at all.
 */
@Serializable
internal data class DiaryProvidersDto(
    @SerialName("petersburg") val petersburg: JsonObject? = null,
    @SerialName("netschool") val netschool: NetSchoolCapabilitiesDto? = null,
)

/** Mirrors `NetSchoolCapabilitiesOut`: the allow-list keys this server signs in to. */
@Serializable
internal data class NetSchoolCapabilitiesDto(
    @SerialName("regions") val regions: List<String> = emptyList(),
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

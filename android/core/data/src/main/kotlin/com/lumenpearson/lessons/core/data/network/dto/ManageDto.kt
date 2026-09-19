package com.lumenpearson.lessons.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The wire shapes of `/api/v1/manage`, mirroring every management model in
 * `server/app/schemas.py`.
 *
 * Same rules as [BundleDto] and [DiaryLessonDto]: an explicit `@SerialName` on
 * every field, a default wherever the server may leave one out, and dates and
 * times left as [String] so one unreadable value costs one row in the mappers
 * rather than the whole response.
 *
 * The `*PatchDto` types are the exception to "a nullable field is an absent
 * field". `NetworkModule.json()` is configured with `explicitNulls = false`,
 * which is right everywhere else and exactly wrong here: the server reads an
 * absent key as "leave it alone" and an explicit `null` as "clear it", so a
 * Kotlin `null` would silently turn "убрать школу" into "ничего не менять".
 * The clearable fields are therefore typed as [JsonElement] — `null` for
 * absent, `JsonNull` for the explicit null that clears — and the repository is
 * the only place that builds them.
 */

/** Mirrors `ManagedClassOut`: the card «⚙️ Класс» draws. */
@Serializable
internal data class ManagedClassDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String = "",
    @SerialName("school") val school: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("timezone") val timezone: String = "",
    @SerialName("timezone_label") val timezoneLabel: String = "",
    @SerialName("join_code") val joinCode: String = "",
    @SerialName("join_mode") val joinMode: String = "open",
    @SerialName("members") val members: Int = 0,
    @SerialName("devices") val devices: Int = 0,
    @SerialName("pending_requests") val pendingRequests: Int = 0,
    @SerialName("bell_schedule_id") val bellScheduleId: Long? = null,
    @SerialName("calendar_ready") val calendarReady: Boolean = false,
)

/** Mirrors `ClassPatch`; see the file comment for why two fields are [JsonElement]. */
@Serializable
internal data class ClassPatchDto(
    @SerialName("name") val name: String? = null,
    @SerialName("school") val school: JsonElement? = null,
    @SerialName("city") val city: JsonElement? = null,
    @SerialName("timezone") val timezone: String? = null,
    /**
     * A plain [String] and not a [JsonElement], unlike the two above it: a
     * class is always in one join mode or the other, so there is no third
     * state for an explicit `null` to mean. Absent is "leave it alone", which
     * is exactly what `explicitNulls = false` makes a Kotlin `null` here.
     */
    @SerialName("join_mode") val joinMode: String? = null,
)

/** Mirrors `ClassDeleteIn`: the class's own name, typed back. */
@Serializable
internal data class ClassDeleteDto(
    @SerialName("confirm_name") val confirmName: String,
)

/** Mirrors `DeletedOut`, the answer to every `DELETE` on this surface. */
@Serializable
internal data class DeletedDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("deleted") val deleted: Boolean = true,
)

/** Mirrors `ManagedSubjectOut`: a dictionary entry with the id that addresses it. */
@Serializable
internal data class ManagedSubjectDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String = "",
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("teacher") val teacher: String? = null,
    @SerialName("color") val color: String? = null,
)

/** Mirrors `SubjectIn`: everything a new subject is made of. */
@Serializable
internal data class SubjectInDto(
    @SerialName("name") val name: String,
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("teacher") val teacher: String? = null,
    @SerialName("color") val color: String? = null,
)

/** Mirrors `SubjectPatch`; see the file comment for why three fields are [JsonElement]. */
@Serializable
internal data class SubjectPatchDto(
    @SerialName("name") val name: String? = null,
    @SerialName("short_name") val shortName: JsonElement? = null,
    @SerialName("teacher") val teacher: JsonElement? = null,
    @SerialName("color") val color: JsonElement? = null,
)

/**
 * Mirrors `SubjectSavedOut`.
 *
 * [moved] is the point of a rename: the timetable, the homework and the substitutions
 * store a subject as text, so all three move with it, and this is how many rows
 * did.
 */
@Serializable
internal data class SubjectSavedDto(
    @SerialName("subject") val subject: ManagedSubjectDto,
    @SerialName("moved") val moved: Int = 0,
)

/** Mirrors `BellPeriodOut` and `BellPeriodIn`, which carry the same three fields. */
@Serializable
internal data class BellPeriodDto(
    @SerialName("index") val index: Int,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
)

/** Mirrors `BellScheduleOut`. */
@Serializable
internal data class BellScheduleDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("periods") val periods: List<BellPeriodDto> = emptyList(),
)

/** Mirrors `BellScheduleIn`: a name, and the rows if they come with it. */
@Serializable
internal data class BellScheduleInDto(
    @SerialName("name") val name: String,
    @SerialName("periods") val periods: List<BellPeriodDto> = emptyList(),
)

/**
 * Mirrors `BellSchedulePatch`.
 *
 * [isDefault] is only ever sent as `true`: the server refuses `false` with a
 * `422`, because a class with no default schedule has no times for an ordinary
 * day. Leaving it absent is how a plain rename says "do not touch the default".
 */
@Serializable
internal data class BellSchedulePatchDto(
    @SerialName("name") val name: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null,
)

/** Mirrors `BellPeriodsIn`: the rows that are now true, all of them. */
@Serializable
internal data class BellPeriodsDto(
    @SerialName("periods") val periods: List<BellPeriodDto>,
)

/** Mirrors `TimetableExportOut`: the paste format, byte for byte. */
@Serializable
internal data class TimetableExportDto(
    @SerialName("text") val text: String = "",
    @SerialName("lessons") val lessons: Int = 0,
)

/** Mirrors `TimetableImportIn`; [replace] is the second tap. */
@Serializable
internal data class TimetableImportInDto(
    @SerialName("text") val text: String,
    @SerialName("replace") val replace: Boolean = false,
)

/** Mirrors `ImportConflictOut`: one weekday the paste would overwrite. */
@Serializable
internal data class ImportConflictDto(
    @SerialName("weekday") val weekday: Int = 0,
    @SerialName("existing") val existing: Int = 0,
    @SerialName("incoming") val incoming: Int = 0,
)

/**
 * Mirrors `TimetableImportOut`.
 *
 * `applied: false` with a non-empty [conflicts] is not a failure — it is the
 * preview the bot shows before «Применить», answered as data.
 */
@Serializable
internal data class TimetableImportDto(
    @SerialName("applied") val applied: Boolean = false,
    @SerialName("days") val days: List<Int> = emptyList(),
    @SerialName("lessons") val lessons: Int = 0,
    @SerialName("bells") val bells: Int = 0,
    @SerialName("conflicts") val conflicts: List<ImportConflictDto> = emptyList(),
    @SerialName("rejected") val rejected: List<String> = emptyList(),
)

/**
 * Mirrors `ManagedDeviceOut`.
 *
 * The three stamps are class wall time, like every other clock on this API, and
 * [owner] is a display name — the Telegram id a device is linked to is never on
 * the wire.
 */
@Serializable
internal data class ManagedDeviceDto(
    @SerialName("id") val id: Long,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("linked") val linked: Boolean = false,
    @SerialName("owner") val owner: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("revoked") val revoked: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("linked_at") val linkedAt: String? = null,
)

/** Mirrors `AuditEntryOut`: one line of «📜 Журнал». */
@Serializable
internal data class AuditEntryDto(
    @SerialName("id") val id: Long,
    @SerialName("action") val action: String = "",
    @SerialName("summary") val summary: String = "",
    @SerialName("who") val who: String? = null,
    @SerialName("at") val at: String? = null,
)

/** Mirrors `AuditPageOut`; `has_more` rather than a total, see the endpoint. */
@Serializable
internal data class AuditPageDto(
    @SerialName("entries") val entries: List<AuditEntryDto> = emptyList(),
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** Mirrors `SubjectHoursOut`: lessons a week, halves included. */
@Serializable
internal data class SubjectHoursDto(
    @SerialName("name") val name: String = "",
    @SerialName("hours") val hours: Double = 0.0,
)

/** Mirrors `StatsOut`: the numbers «📊 Статистика» shows. */
@Serializable
internal data class StatsDto(
    @SerialName("today") val today: String? = null,
    @SerialName("lessons_per_week") val lessonsPerWeek: Double = 0.0,
    @SerialName("subjects_count") val subjectsCount: Int = 0,
    @SerialName("subjects") val subjects: List<SubjectHoursDto> = emptyList(),
    @SerialName("homework_open") val homeworkOpen: Int = 0,
    @SerialName("homework_total") val homeworkTotal: Int = 0,
    @SerialName("members_by_role") val membersByRole: Map<String, Int> = emptyMap(),
    @SerialName("devices_active") val devicesActive: Int = 0,
    @SerialName("overrides_upcoming") val overridesUpcoming: Int = 0,
    @SerialName("events_upcoming") val eventsUpcoming: Int = 0,
)

/** Mirrors `AccessRequestOut`: somebody waiting for a role. */
@Serializable
internal data class AccessRequestDto(
    @SerialName("id") val id: Long,
    @SerialName("who") val who: String = "",
    @SerialName("requested_role") val requestedRole: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Mirrors `RequestDecisionIn`: absent means "the role that was asked for". */
@Serializable
internal data class RequestDecisionInDto(
    @SerialName("role") val role: String? = null,
)

/** Mirrors `RequestDecisionOut`; [role] is `null` for a declined request. */
@Serializable
internal data class RequestDecisionDto(
    @SerialName("id") val id: Long,
    @SerialName("status") val status: String = "",
    @SerialName("role") val role: String? = null,
    @SerialName("who") val who: String = "",
)

/** Mirrors `SchoolOut`: one row of the school directory. */
@Serializable
internal data class SchoolDto(
    @SerialName("name") val name: String = "",
    @SerialName("full_name") val fullName: String = "",
    @SerialName("ogrn") val ogrn: String? = null,
    @SerialName("inn") val inn: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("active") val active: Boolean = true,
)

/** Mirrors `SchoolSearchOut`; see [SchoolPage] for what `truncated` is not. */
@Serializable
internal data class SchoolSearchDto(
    @SerialName("items") val items: List<SchoolDto> = emptyList(),
    @SerialName("page") val page: Int = 1,
    @SerialName("pages") val pages: Int = 1,
    @SerialName("total") val total: Int = 0,
    @SerialName("truncated") val truncated: Boolean = false,
)

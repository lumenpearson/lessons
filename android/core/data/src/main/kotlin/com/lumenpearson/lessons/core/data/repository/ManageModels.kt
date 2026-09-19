package com.lumenpearson.lessons.core.data.repository

import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * The management surface's value types, as the screens above this line see them.
 *
 * Nothing here carries a wire field or a date string, for the same reason
 * nothing in [DiaryLesson] does: the screens are allowed to know what a bell
 * schedule is and not allowed to know what JSON is.
 *
 * Every timestamp is **class wall time** — the server converts before sending,
 * because an admin in Vladivostok reading a Moscow server's log must not see
 * yesterday evening against this morning's change. That is why they are
 * [LocalDateTime] and not [java.time.Instant]: there is no instant here to be
 * had, only the time the class says it was.
 */

/**
 * How a phone is let into the class.
 *
 * An enum rather than the raw string it arrives as, because every screen that
 * asks this question asks it twice — which icon, which sentence — and a typo in
 * one of the two comparisons would be a row that says «открытый» under a lock.
 *
 * [fromWire] reads anything it does not recognise as [OPEN], and that direction
 * is deliberate. A phone older than the server will meet modes this build has
 * never heard of, and the two wrong answers are not equally wrong: [OPEN] is
 * what every class was before this feature existed, so it is the reading that
 * describes the class the user is most likely looking at, and it is the reading
 * whose screen — «код класса работает» — is checked against the server on the
 * very next join. Refusing to decode, or defaulting to [INVITE], would hide a
 * working class code behind a padlock nobody can explain.
 */
enum class ClassJoinMode {

    /** The class code admits whoever types it. What every class starts as. */
    OPEN,

    /**
     * The class code admits nobody; a phone gets in on a personal one-time
     * code the bot hands to a member.
     */
    INVITE,
    ;

    /** The spelling `server/app/models.py:JoinMode` uses. */
    fun toWire(): String = when (this) {
        OPEN -> WIRE_OPEN
        INVITE -> WIRE_INVITE
    }

    companion object {

        private const val WIRE_OPEN: String = "open"
        private const val WIRE_INVITE: String = "invite"

        /** Anything but a known mode is [OPEN]; see the type's own comment. */
        fun fromWire(raw: String?): ClassJoinMode =
            if (raw?.trim()?.lowercase() == WIRE_INVITE) INVITE else OPEN
    }
}

/** The card «⚙️ Класс» draws, with the counts under it. */
data class ManagedClass(
    val id: Long,
    val name: String,
    val school: String?,
    val city: String?,
    val timezone: String,
    /** "МСК+2 (UTC+5) · Екатеринбург" — the bot's own label, so it is not built twice. */
    val timezoneLabel: String,
    val joinCode: String,
    /** Whether [joinCode] is enough on its own, or only a bot invite is. */
    val joinMode: ClassJoinMode,
    val members: Int,
    val devices: Int,
    val pendingRequests: Int,
    val bellScheduleId: Long?,
    val calendarReady: Boolean,
)

/** One school out of the directory, as the picker draws it. */
data class School(
    /** What goes on a button, and what is written to the class when it is picked. */
    val name: String,
    /** The register's own spelling — the only unambiguous name there is. */
    val fullName: String,
    val ogrn: String?,
    val address: String?,
    val city: String?,
    val region: String?,
    /** False for one the register has closed. Shown and marked, never hidden. */
    val active: Boolean,
) {
    /** The name, and the city when there is one — two schools share a number. */
    val label: String get() = city?.let { "$name · $it" } ?: name
}

/**
 * One screenful of results.
 *
 * [truncated] is **not** «есть ещё страницы» — [pages] counts those. It means
 * the directory's own ceiling of twenty rows was reached, so this is the first
 * twenty of an unknown number and the way to the rest is a longer query, not a
 * next page. A screen that shows «найдено 20» for a search matching three
 * hundred schools has read [total] and ignored this.
 */
data class SchoolPage(
    val items: List<School>,
    val page: Int,
    val pages: Int,
    val total: Int,
    val truncated: Boolean,
)

/**
 * The four fields of the class card that can be edited, as one intention.
 *
 * All four travel together rather than as a patch of "what changed", because
 * the screen that produces this is a form with four boxes in it and the server
 * reads a field that is present and equal to what it already holds as a
 * no-change. A blank [school] or [city] is `null` here and an explicit `null`
 * on the wire, which is the only way to say «убрать».
 */
data class ClassEdit(
    val name: String,
    val school: String?,
    val city: String?,
    val timezone: String,
)

/** One entry of the subject dictionary, with the id that addresses it. */
data class ManagedSubject(
    val id: Long,
    val name: String,
    val shortName: String?,
    val teacher: String?,
    val color: String?,
)

/** Everything a subject is made of, as the add and edit forms fill it in. */
data class SubjectForm(
    val name: String,
    val shortName: String?,
    val teacher: String?,
    val color: String?,
)

/**
 * A saved subject, and how many rows a rename carried with it.
 *
 * [moved] is zero for every edit that is not a rename. It matters because the
 * timetable, the homework and the substitutions store a subject as *text*, so a rename
 * is a cascade the admin cannot see the effect of anywhere else.
 */
data class SubjectSaved(
    val subject: ManagedSubject,
    val moved: Int,
)

/** One lesson's worth of bells: its number and the two times. */
data class BellPeriod(
    val index: Int,
    val startsAt: LocalTime,
    val endsAt: LocalTime,
)

/** A named set of bells — «Обычное», «Сокращённое», «Суббота». */
data class BellSchedule(
    val id: Long,
    val name: String,
    /** The one the class runs on when no day says otherwise. */
    val isDefault: Boolean,
    val periods: List<BellPeriod>,
)

/**
 * A bells write, and what it took away.
 *
 * Beside [BellSchedule] rather than inside it, because a count of lessons this
 * *request* silenced is not a property of the schedule: it is zero on every
 * read of the same row, and a model carrying a field only one of its producers
 * ever fills is how a renderer ends up written against a shape nothing builds.
 *
 * @property silencedLessons lessons that stopped ringing, counted in rows. One
 *   lesson number under two weekdays is two lessons nobody will see, not one.
 *   Nothing is deleted on the server — the rows are simply past the schedule's
 *   last rung now, and drawn on no phone, in no widget, in no calendar feed and
 *   in no digest. The bot says this out loud in an alert; it is why the phone
 *   is told at all.
 */
data class BellsWritten(
    val schedule: BellSchedule,
    val silencedLessons: Int,
)

/** The weekly template as text, in the format import reads back. */
data class TimetableExport(
    val text: String,
    val lessons: Int,
)

/** One weekday a paste would overwrite: what is there now, what would replace it. */
data class ImportConflict(
    /** 1 = Monday … 7 = Sunday, as everywhere else on this API. */
    val weekday: Int,
    val existing: Int,
    val incoming: Int,
)

/**
 * What an import did, or what it refused to do.
 *
 * [applied] `false` with conflicts is the preview the bot shows before
 * «Применить»: nothing was written, and these are the days that would be.
 * [rejected] echoes the lines the parser could not read, so two typos can be
 * fixed without re-reading the whole paste.
 */
data class TimetableImport(
    val applied: Boolean,
    val days: List<Int>,
    val lessons: Int,
    val bells: Int,
    val conflicts: List<ImportConflict>,
    val rejected: List<String>,
)

/**
 * One phone on the class's list.
 *
 * [role] is a lookup on the server, never a stored field: a device acts with
 * whatever role its owner holds right now, so this list has already changed by
 * the time it is drawn if somebody was demoted in the bot.
 */
data class ManagedDevice(
    val id: Long,
    val deviceName: String?,
    val linked: Boolean,
    val owner: String?,
    val role: ClassRole?,
    val revoked: Boolean,
    val createdAt: LocalDateTime?,
    val lastSeenAt: LocalDateTime?,
    val linkedAt: LocalDateTime?,
)

/**
 * One line of the audit log.
 *
 * [action] is the machine tag — `subject.rename`, `timetable.import` — and
 * [summary] is the sentence, in Russian, written by whichever surface made the
 * change. It is plain text and is never put anywhere that would read it as
 * markup.
 */
data class AuditEntry(
    val id: Long,
    val action: String,
    val summary: String,
    val who: String?,
    val at: LocalDateTime?,
)

/** One page of the log. [hasMore] rather than a total; see `GET /manage/log`. */
data class AuditPage(
    val entries: List<AuditEntry>,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
)

/** Lessons a week for one subject; a fortnightly lesson counts a half. */
data class SubjectHours(
    val name: String,
    val hours: Double,
)

/** The numbers «📊 Статистика» shows, without the sentence around them. */
data class ClassStats(
    val today: LocalDate?,
    val lessonsPerWeek: Double,
    val subjectsCount: Int,
    val subjects: List<SubjectHours>,
    val homeworkOpen: Int,
    val homeworkTotal: Int,
    val membersByRole: Map<ClassRole, Int>,
    val devicesActive: Int,
    val overridesUpcoming: Int,
    val eventsUpcoming: Int,
)

/** Somebody with a read-only phone waiting for a role. */
data class AccessRequest(
    val id: Long,
    val who: String,
    val requestedRole: ClassRole?,
    val message: String?,
    val createdAt: LocalDateTime?,
)

/** What an approve or a decline did. [role] is `null` for a decline. */
data class RequestDecision(
    val id: Long,
    val approved: Boolean,
    val role: ClassRole?,
    val who: String,
)

/**
 * Why a management call did not work, as something the UI can switch on.
 *
 * A sealed hierarchy rather than a message, for the reason [DiaryFailure] is
 * one: these lead to different screens, and the difference that matters most
 * cannot be seen in a status code at all. Two of the `403`s mean **the role
 * changed under the user** — the page they are standing on is no longer theirs
 * — and the third, on approving a request, means only that *this* grant is
 * above their level. Treating the third as the first would throw an
 * administrator off the page for pressing a button they were never allowed to
 * press; treating the first as the third would leave them on a page where
 * everything fails.
 *
 * It extends [Exception] so that it travels inside the `Result` every
 * repository method returns, as the diary's failures already do.
 */
sealed class ManageFailure(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** `401`: the device token itself is gone. Nothing on this page can help. */
    data object SignedOut : ManageFailure("The device token is no longer valid")

    /**
     * `403 device is not linked`: this phone is tied to no Telegram account, so
     * there is no role to look up. The page has to go; linking is done on the
     * class page, not here.
     */
    data object NotLinked : ManageFailure("This device is not linked to a Telegram account")

    /**
     * `403 <role> role required`: the linked account no longer holds the role
     * this page is for. The role was demoted in the bot while the page was open,
     * which is exactly the case the server exists to catch.
     *
     * @property required the role the server named — "admin" or "owner" — so the
     *   message can say which one is missing rather than «нет прав».
     */
    data class RoleLost(val required: String?) :
        ManageFailure("The linked account no longer holds the ${required ?: "required"} role")

    /**
     * Any other `403`. On this surface it is one of the two grant guards:
     * nobody may hand out a role at or above their own, and nobody may change a
     * peer's. Neither says anything about the page as a whole.
     */
    data class NotAllowed(val detail: String?) :
        ManageFailure(detail ?: "The server refused this operation")

    /** `404`: a row that is not this class's, or one somebody else just deleted. */
    data object NotFound : ManageFailure("No such row in this class")

    /**
     * `409`: well formed, and refused by the class's own state — a subject name
     * already taken, a bell schedule days still point at, a device that is not
     * linked. [detail] is the server's own sentence, which is the only thing
     * that tells those three apart.
     */
    data class Refused(val detail: String?) :
        ManageFailure(detail ?: "The class's own state refuses this")

    /** `422`: the request did not validate. A bug on our side, or a typo in a paste. */
    data class Invalid(val detail: String?) :
        ManageFailure(detail ?: "The server could not accept these values")

    /**
     * `503`: the feature is switched off or its upstream is not answering.
     *
     * Its own case rather than an [Unexpected] because nothing is broken and
     * the screen has somewhere to go: the school directory needs a key this
     * deployment may not have, and the answer to that is to let the name be
     * typed rather than to retry or to report a bug. [detail] is the server's
     * own Russian sentence, which already says exactly that.
     */
    data class Unavailable(val detail: String?) :
        ManageFailure(detail ?: "This feature is not available right now")

    /** No answer at all: no network, wrong address, a timeout. */
    data class Offline(val reason: Throwable) :
        ManageFailure("Could not reach the server", reason)

    /** Anything else, kept with its cause so a bug report can carry it. */
    data class Unexpected(val code: Int?, val reason: Throwable?) :
        ManageFailure("Unexpected management failure (${code ?: "no status"})", reason)

    /**
     * Whether this answer means the page itself is gone.
     *
     * The one question the management page asks of every failure it sees, which
     * is why it is a property here and not an `is` chain repeated in eight
     * screens.
     */
    val endsTheSession: Boolean
        get() = this is SignedOut || this is NotLinked || this is RoleLost

    companion object {

        /** The exact `detail` the server sends for an unlinked device. */
        const val DETAIL_NOT_LINKED: String = "device is not linked"

        /** The tail of `"admin role required"`, `"owner role required"`, … */
        private const val DETAIL_ROLE_SUFFIX: String = "role required"

        /** Classifies whatever a call threw. */
        fun of(failure: Throwable): ManageFailure = when (failure) {
            is ManageFailure -> failure
            is HttpException -> ofStatus(failure.code(), detailOf(errorBodyOf(failure)))
            is IOException -> Offline(failure)
            else -> Unexpected(code = null, reason = failure)
        }

        /**
         * The rule itself, over a status and the server's `detail`.
         *
         * Separated from [of] so that it can be read — and tested — without a
         * Retrofit response to build: every sentence about who is thrown off
         * this page is in these fifteen lines.
         *
         * The `403` branch reads the detail because the status alone cannot
         * answer the only question worth asking about a `403` here. It compares
         * against the two spellings `app/api/manage.py` writes, and falls
         * through to [NotAllowed] for anything it does not recognise — the safe
         * direction, because [NotAllowed] leaves the user where they are.
         */
        fun ofStatus(code: Int, detail: String? = null): ManageFailure {
            val said = detail?.trim().orEmpty()
            return when (code) {
                401 -> SignedOut
                403 -> when {
                    said.equals(DETAIL_NOT_LINKED, ignoreCase = true) -> NotLinked
                    // `dropLast`, not `removeSuffix`: the guard above matched
                    // ignoring case and `removeSuffix` does not, so the two
                    // disagreed on anything but the server's current lowercase
                    // spelling — and the role then came back as the whole
                    // sentence, so the screen said «нет роли admin ROLE
                    // REQUIRED». Dead today, and the live risk is precisely
                    // that the two spellings are written in two places: the
                    // day `manage.py` capitalises that message, the guard
                    // still fires and the extraction silently stops working.
                    // The length is the same whatever the case, so taking it
                    // off by length cannot drift from the test above it.
                    said.endsWith(DETAIL_ROLE_SUFFIX, ignoreCase = true) ->
                        RoleLost(said.dropLast(DETAIL_ROLE_SUFFIX.length).trim().ifBlank { null })

                    else -> NotAllowed(said.ifBlank { null })
                }

                404 -> NotFound
                409 -> Refused(said.ifBlank { null })
                422 -> Invalid(said.ifBlank { null })
                503 -> Unavailable(said.ifBlank { null })
                else -> Unexpected(code = code, reason = null)
            }
        }

        /**
         * The `detail` out of a FastAPI error body, in either of its two shapes.
         *
         * A raised `HTTPException` puts a string there; a validation failure
         * puts a list of objects, of which the first `msg` is the one worth
         * reading. Anything else — an HTML error page from a proxy, an empty
         * body — is `null` rather than a guess, because a truncated `<html>` on
         * screen is worse than no detail at all.
         */
        fun detailOf(body: String?): String? {
            val text = body?.trim().orEmpty()
            if (text.isEmpty()) return null
            val root = runCatching { Lenient.parseToJsonElement(text) }.getOrNull()
            val detail = (root as? JsonObject)?.get("detail") ?: return null
            return when (detail) {
                is JsonPrimitive -> detail.content.trim().ifBlank { null }
                is JsonArray -> detail.firstOrNull()
                    ?.let { (it as? JsonObject)?.get("msg") }
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    ?.ifBlank { null }

                else -> null
            }
        }

        /**
         * Reads the error body once, tolerating every way that can fail.
         *
         * `errorBody()` is a one-shot stream that may already be closed by the
         * time this runs; losing the detail costs a nicer message, and throwing
         * here would lose the failure itself.
         */
        private fun errorBodyOf(failure: HttpException): String? =
            runCatching { failure.response()?.errorBody()?.string() }.getOrNull()

        /** Its own parser: the network one is not reachable from this module's API. */
        private val Lenient = Json { ignoreUnknownKeys = true }
    }
}

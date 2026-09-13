package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ImportConflict
import java.time.DayOfWeek

/**
 * Everything the management screens decide before anything is sent.
 *
 * A separate file for the same reason `DiaryPresentation` is one: all of it is
 * testable and none of it needs a device. It is also the half of this feature
 * that is easiest to get subtly wrong — a form that lets through a name the
 * server will refuse turns a typo into a round trip and an English error
 * message, and a conflict summary that miscounts is how somebody agrees to
 * overwrite a week they meant to keep.
 *
 * None of it is a permission check and none of it is authoritative. The server
 * validates every one of these rules again, because it has to; these exist so
 * that the common mistakes are answered in the sheet the finger is already in.
 */

// --------------------------------------------------------------------------
// Where a class can be
// --------------------------------------------------------------------------

/**
 * One of the eleven zones a class may be set to.
 *
 * @property offset the offset from Moscow, which is how Russian schedules,
 *   transport timetables and television listings are actually written.
 */
data class ManagedTimeZone(
    val id: String,
    val offset: String,
    val cities: String,
) {
    /** "МСК+2 (UTC+5) · Екатеринбург" — the same line the bot prints. */
    val label: String get() = "$offset · $cities"
}

/**
 * `RUSSIAN_TIMEZONES` in `server/app/timezones.py`, mirrored.
 *
 * Mirrored rather than fetched because there is no endpoint that lists them and
 * because the list is the country's, not the deployment's: it changes when
 * Russia changes its zones, which is on the order of once a decade. A class
 * whose stored zone is not in here — a school outside Russia, configured by
 * hand — keeps it: [zoneOptions] puts the current value back at the top rather
 * than offering a picker that cannot express what the class already is.
 *
 * The labels are not in `strings_admin.xml`: they are Russian place names and a
 * Moscow offset, which is what they say in either language of this app.
 */
val RussianTimeZones: List<ManagedTimeZone> = listOf(
    ManagedTimeZone("Europe/Kaliningrad", "МСК−1 (UTC+2)", "Калининград"),
    ManagedTimeZone("Europe/Moscow", "МСК (UTC+3)", "Москва, Санкт-Петербург"),
    ManagedTimeZone("Europe/Samara", "МСК+1 (UTC+4)", "Самара, Ижевск"),
    ManagedTimeZone("Asia/Yekaterinburg", "МСК+2 (UTC+5)", "Екатеринбург, Уфа, Пермь"),
    ManagedTimeZone("Asia/Omsk", "МСК+3 (UTC+6)", "Омск"),
    ManagedTimeZone("Asia/Krasnoyarsk", "МСК+4 (UTC+7)", "Красноярск, Новосибирск"),
    ManagedTimeZone("Asia/Irkutsk", "МСК+5 (UTC+8)", "Иркутск, Улан-Удэ"),
    ManagedTimeZone("Asia/Yakutsk", "МСК+6 (UTC+9)", "Якутск, Чита"),
    ManagedTimeZone("Asia/Vladivostok", "МСК+7 (UTC+10)", "Владивосток, Хабаровск"),
    ManagedTimeZone("Asia/Magadan", "МСК+8 (UTC+11)", "Магадан, Южно-Сахалинск"),
    ManagedTimeZone("Asia/Kamchatka", "МСК+9 (UTC+12)", "Петропавловск-Камчатский"),
)

/**
 * The eleven, plus [current] if it is none of them.
 *
 * A picker that silently dropped an unrecognised zone would offer an admin a
 * list in which their own class does not appear, and moving off it would be one
 * tap with no way back.
 */
fun zoneOptions(current: String?): List<ManagedTimeZone> {
    val chosen = current?.trim().orEmpty()
    if (chosen.isEmpty() || RussianTimeZones.any { it.id == chosen }) return RussianTimeZones
    return listOf(ManagedTimeZone(chosen, chosen, "")) + RussianTimeZones
}

// --------------------------------------------------------------------------
// What a form may send
// --------------------------------------------------------------------------

/**
 * Why a form cannot be sent yet.
 *
 * An enum rather than a sentence so that the rule and its wording stay apart:
 * the rule is a mirror of `server/app/schemas.py` and belongs next to the
 * limits it copies, the wording belongs in `strings_admin.xml` in two
 * languages.
 */
enum class FormProblem {
    NAME_BLANK,
    NAME_TOO_LONG,
    SHORT_NAME_TOO_LONG,
    TEACHER_TOO_LONG,
    SCHOOL_TOO_LONG,
    CITY_TOO_LONG,
    COLOUR_UNREADABLE,
    NO_PERIODS,
    TOO_MANY_PERIODS,
    PERIOD_NUMBER_REPEATED,
    PERIOD_NUMBER_OUT_OF_RANGE,
    PERIOD_ENDS_BEFORE_IT_STARTS,
    EMPTY_PASTE,
    PASTE_TOO_LONG,
    NAME_DOES_NOT_MATCH,
}

/** `Field(max_length=…)` in `server/app/schemas.py`, in one place. */
private object Limits {
    const val CLASS_NAME = 64
    const val SCHOOL = 200
    const val CITY = 120
    const val SUBJECT_NAME = 120
    const val SHORT_NAME = 16
    const val TEACHER = 120
    const val BELL_NAME = 64
    const val PERIODS = 20
    const val PERIOD_INDEX = 20
    const val PASTE = 20_000
}

/**
 * `#5b6abf` / `5B6ABF` -> `#5B6ABF`; blank, `-` and `—` mean "no colour".
 *
 * The same normalisation `_clean_colour` does on the server, done here too so
 * that the swatch on the form is the colour that will be stored — otherwise an
 * admin types six hex digits, sees nothing change, and types them again.
 *
 * @return the normalised colour, `null` for "cleared", and [String] `""` never:
 *   an unreadable value is reported by [subjectFormProblem], not silently
 *   dropped.
 */
fun normaliseSubjectColour(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "-" || text == "—") return null
    val digits = text.removePrefix("#")
    if (!ColourDigits.matches(digits)) return null
    return "#${digits.uppercase()}"
}

/** Whether [raw] is something [normaliseSubjectColour] can read at all. */
private fun isColourReadable(raw: String?): Boolean {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "-" || text == "—") return true
    return ColourDigits.matches(text.removePrefix("#"))
}

/** `_COLOUR_RE` in `server/app/schemas.py`, without its anchors. */
private val ColourDigits = Regex("[0-9a-fA-F]{6}")

/** The class card's four boxes, as the server would judge them. */
fun classFormProblem(
    name: String,
    school: String,
    city: String,
): FormProblem? = when {
    name.trim().isEmpty() -> FormProblem.NAME_BLANK
    name.trim().length > Limits.CLASS_NAME -> FormProblem.NAME_TOO_LONG
    school.trim().length > Limits.SCHOOL -> FormProblem.SCHOOL_TOO_LONG
    city.trim().length > Limits.CITY -> FormProblem.CITY_TOO_LONG
    else -> null
}

/** The add and edit forms of a subject; the same four rules serve both. */
fun subjectFormProblem(
    name: String,
    shortName: String,
    teacher: String,
    colour: String,
): FormProblem? = when {
    name.trim().isEmpty() -> FormProblem.NAME_BLANK
    name.trim().length > Limits.SUBJECT_NAME -> FormProblem.NAME_TOO_LONG
    shortName.trim().length > Limits.SHORT_NAME -> FormProblem.SHORT_NAME_TOO_LONG
    teacher.trim().length > Limits.TEACHER -> FormProblem.TEACHER_TOO_LONG
    !isColourReadable(colour) -> FormProblem.COLOUR_UNREADABLE
    else -> null
}

/** A bell schedule's name, which is all a schedule needs to exist. */
fun bellScheduleNameProblem(name: String): FormProblem? = when {
    name.trim().isEmpty() -> FormProblem.NAME_BLANK
    name.trim().length > Limits.BELL_NAME -> FormProblem.NAME_TOO_LONG
    else -> null
}

/**
 * The rows of a schedule, as `BellPeriodsIn` judges them.
 *
 * An empty list is refused here as it is there: the stored schedule is what the
 * class runs on, and «пустой присылкой звонки не стереть» is a rule about
 * intent, not about validation — a client that sends nothing almost always
 * means it did not mean to send at all.
 */
fun bellPeriodsProblem(periods: List<BellPeriod>): FormProblem? {
    if (periods.isEmpty()) return FormProblem.NO_PERIODS
    if (periods.size > Limits.PERIODS) return FormProblem.TOO_MANY_PERIODS
    if (periods.any { it.index !in 1..Limits.PERIOD_INDEX }) {
        return FormProblem.PERIOD_NUMBER_OUT_OF_RANGE
    }
    if (periods.map { it.index }.toSet().size != periods.size) {
        return FormProblem.PERIOD_NUMBER_REPEATED
    }
    if (periods.any { !it.endsAt.isAfter(it.startsAt) }) {
        return FormProblem.PERIOD_ENDS_BEFORE_IT_STARTS
    }
    return null
}

/** A paste that is empty, or longer than the endpoint will read. */
fun pasteProblem(text: String): FormProblem? = when {
    text.isBlank() -> FormProblem.EMPTY_PASTE
    text.length > Limits.PASTE -> FormProblem.PASTE_TOO_LONG
    else -> null
}

/**
 * Whether what was typed is the class's name.
 *
 * Checked here only so the button can be dark until it is; the check that
 * matters is the server's, because `DELETE /manage/class` is reachable without
 * this sheet. Trimmed and compared exactly — the server trims and compares
 * exactly — so «9а» does not delete «9А».
 */
fun confirmsClassName(typed: String, className: String): Boolean =
    typed.trim() == className.trim() && className.isNotBlank()

/** The problem, if the typed name is not it. */
fun deleteConfirmProblem(typed: String, className: String): FormProblem? =
    if (confirmsClassName(typed, className)) null else FormProblem.NAME_DOES_NOT_MATCH

// --------------------------------------------------------------------------
// What an import would overwrite
// --------------------------------------------------------------------------

/**
 * One weekday of an import that would overwrite something.
 *
 * [weekday] is `null` for a number outside 1..7, which the server does not
 * send and which is therefore a bug somewhere rather than a day: the row is
 * still shown, because a conflict nobody can name is still a conflict and
 * hiding it would be the one thing this screen must never do.
 */
data class ImportConflictLine(
    /** The weekday as it arrived, kept so an unnameable one can still be shown. */
    val number: Int,
    val weekday: DayOfWeek?,
    val existing: Int,
    val incoming: Int,
)

/**
 * What a refused import stands to overwrite, counted.
 *
 * The one number an admin actually decides on is [existing]: it is how many
 * lessons disappear if they press «Заменить». [days] and [incoming] are the
 * shape of what arrives in their place.
 */
data class ImportConflictSummary(
    val days: Int,
    val existing: Int,
    val incoming: Int,
    val lines: List<ImportConflictLine>,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Turns the server's conflict list into the sentence the sheet asks for consent
 * with.
 *
 * Sorted by weekday rather than left in the order it arrived, so that a paste
 * covering the week reads Monday to Sunday whatever order the server chose, and
 * an unnameable weekday sorts last instead of breaking the run.
 */
fun summariseImportConflicts(conflicts: List<ImportConflict>): ImportConflictSummary {
    val lines = conflicts
        .sortedBy { if (it.weekday in 1..7) it.weekday else Int.MAX_VALUE }
        .map {
            ImportConflictLine(
                number = it.weekday,
                weekday = weekdayOrNull(it.weekday),
                existing = it.existing,
                incoming = it.incoming,
            )
        }
    return ImportConflictSummary(
        days = lines.size,
        existing = lines.sumOf { it.existing },
        incoming = lines.sumOf { it.incoming },
        lines = lines,
    )
}

/** `null` for anything that is not 1..7, as [ImportConflictLine.weekday] is. */
fun weekdayOrNull(weekday: Int): DayOfWeek? =
    if (weekday in 1..7) DayOfWeek.of(weekday) else null

// --------------------------------------------------------------------------
// Roles
// --------------------------------------------------------------------------

/**
 * The roles this account may hand out, weakest first.
 *
 * `can_grant` in `server/app/bot/roles.py`, mirrored, all three of its clauses:
 * `OWNER` is never grantable — the deployment's `OWNER_IDS` is the only source
 * of it — nobody below an administrator may grant anything at all, and nobody
 * may grant at or above their own level. The server checks all three again, as
 * it must; the list on screen is only tidiness.
 *
 * A `null` role — the answer before `/me` has come back — offers nothing, for
 * the same reason [isClassManager] answers `false` to it.
 */
fun grantableRoles(actor: ClassRole?): List<ClassRole> {
    if (actor == null || actor.ordinal < ClassRole.ADMIN.ordinal) return emptyList()
    return listOf(ClassRole.VIEWER, ClassRole.EDITOR, ClassRole.ADMIN)
        .filter { it.ordinal < actor.ordinal }
}

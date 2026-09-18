package com.lumenpearson.lessons.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** How a whole date deviates from the normal weekly rhythm. */
enum class DayKind {
    NORMAL,
    HOLIDAY,
    SHORTENED,
    REMOTE,
    ;

    companion object {
        fun fromWire(value: String): DayKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NORMAL
    }
}

/** Anything on the day's timeline that is not a lesson. */
enum class EventKind {
    EVENT,
    CANTEEN,
    EXAM,
    TRIP,
    MEETING,
    ;

    companion object {
        fun fromWire(value: String): EventKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: EVENT
    }
}

data class Lesson(
    val index: Int,
    val subject: String,
    val startsAt: LocalTime,
    val endsAt: LocalTime,
    val room: String? = null,
    val teacher: String? = null,
    val colorHex: String? = null,
    val isReplaced: Boolean = false,
    val isCancelled: Boolean = false,
    val note: String? = null,
)

data class SchoolEvent(
    val title: String,
    val kind: EventKind,
    val startsAt: LocalTime,
    val endsAt: LocalTime,
    val location: String? = null,
    val coversLesson: Boolean = false,
)

data class HomeworkItem(
    val subject: String,
    val text: String,
    val attachmentUrl: String? = null,
)

data class SchoolDay(
    val date: LocalDate,
    val weekday: Int,
    val kind: DayKind = DayKind.NORMAL,
    val lessons: List<Lesson> = emptyList(),
    val events: List<SchoolEvent> = emptyList(),
    val homework: List<HomeworkItem> = emptyList(),
    val note: String? = null,
) {
    /** Lessons that actually take place, in timeline order. */
    val activeLessons: List<Lesson>
        get() = lessons.filterNot { it.isCancelled }.sortedBy { it.startsAt }

    val hasLessons: Boolean get() = activeLessons.isNotEmpty()

    val firstLesson: Lesson? get() = activeLessons.firstOrNull()

    val lastLesson: Lesson? get() = activeLessons.lastOrNull()
}

/** Четверти or полугодия — how this class's year is cut up. */
enum class TermKind {
    QUARTER,
    SEMESTER,
    ;

    companion object {
        /**
         * Unknown wire values become [QUARTER] rather than throwing: a server
         * that learns a third scheme must not stop an older client from
         * drawing a timetable, and quarters are what most classes are taught in.
         */
        fun fromWire(raw: String?): TermKind =
            if (raw?.lowercase() == "semester") SEMESTER else QUARTER
    }
}

/**
 * One term, as the school actually runs it.
 *
 * The dates come from the server because they are the school's own and they
 * move; deriving them here would be a second answer to a question an admin has
 * already answered.
 */
data class Term(
    val index: Int,
    val kind: TermKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
) {
    operator fun contains(date: LocalDate): Boolean = date >= startsOn && date <= endsOn

    // No `label` here, deliberately. «2 четверть» used to be built in this file
    // — a pure JVM module with no resources and no way to have any — and the
    // calendar's header drew it beside a period label that *did* come out of
    // `values-en/`, so an English phone read «October 2026 · 1 четверть». The
    // wording now lives with the screen that shows it; see `Term.label()` in
    // `:app`'s `ui/week`. This type carries the index and the kind, which is
    // everything a sentence needs and nothing a language decides.
}

data class SchoolClassInfo(
    val id: Long,
    val name: String,
    val grade: Int? = null,
    val letter: String? = null,
    val school: String? = null,
    val city: String? = null,
    val timeZoneId: String = "Europe/Moscow",
    val termKind: TermKind = TermKind.QUARTER,
    val terms: List<Term> = emptyList(),
) {
    /** The term holding [date], or `null` — каникулы are a real answer. */
    fun termAt(date: LocalDate): Term? = terms.firstOrNull { date in it }

    /**
     * The school's zone, falling back to the device's if the server sent
     * something this Android build has no tzdata for.
     *
     * Russia spans eleven zones, so the school's zone and the phone's are
     * routinely different - a parent in Moscow watching a school in Novosibirsk
     * must see that school's bells, not their own clock's.
     */
    val zone: ZoneId
        get() = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.systemDefault() }
}

/**
 * The full offline snapshot. One of these is enough to render every screen and
 * to run the widget for as long as [days] covers, with no network at all.
 */
data class Timetable(
    val schoolClass: SchoolClassInfo,
    val days: List<SchoolDay> = emptyList(),
    val nextSchoolDay: SchoolDay? = null,
    val syncedAtEpochMillis: Long = 0L,
) {
    fun day(date: LocalDate): SchoolDay? = days.firstOrNull { it.date == date }

    /**
     * "Now" as the school experiences it.
     *
     * Every caller that derives a [DayState] must go through this rather than
     * `LocalDateTime.now()`: the schedule is stored as the school's wall time,
     * so comparing it against the device's wall time is only correct by
     * coincidence.
     */
    fun nowAtSchool(clock: java.time.Clock = java.time.Clock.systemUTC()): LocalDateTime =
        atSchool(clock.instant())

    /**
     * [nowAtSchool] for a caller that already holds the instant.
     *
     * A screen that ticks has to read the clock somewhere other than where it
     * derives the state — the ticker emits, the state is rebuilt from what it
     * emitted — and the instant is the only form of "now" that can cross that
     * gap without silently becoming the device's wall time on the way.
     */
    fun atSchool(instant: java.time.Instant): LocalDateTime =
        LocalDateTime.ofInstant(instant, schoolClass.zone)

    /**
     * First day after [after] that has lessons. Falls back to [nextSchoolDay],
     * which the server resolves beyond the cached window so long holidays still
     * produce an answer.
     */
    fun schoolDayAfter(after: LocalDate): SchoolDay? =
        days.filter { it.date > after && it.hasLessons }.minByOrNull { it.date }
            ?: nextSchoolDay?.takeIf { it.date > after }
}

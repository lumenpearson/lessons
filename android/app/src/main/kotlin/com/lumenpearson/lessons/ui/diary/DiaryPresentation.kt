package com.lumenpearson.lessons.ui.diary

import androidx.annotation.StringRes
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.diary.DiaryDateRange
import com.lumenpearson.lessons.core.data.diary.DiaryWindows
import com.lumenpearson.lessons.core.data.repository.DiaryEdit
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the diary screens decide, with nothing on screen.
 *
 * It is a separate file because all of it is testable and none of it needs a
 * device: the week the schedule is grouped into, the arithmetic that ignores an
 * "Н" when averaging marks, and the date window the marks are asked for — which
 * has a hard 62-day limit on the server and is therefore a rule rather than a
 * preference. A composable that computed any of this inline would be a rule
 * nobody could test.
 */

/** The two halves of the section; a segmented picker switches between them. */
enum class DiaryTab {
    SCHEDULE,
    GRADES,
}

/**
 * One day of the week view: its lessons, and the homework due on it.
 *
 * The homework is *due* on this date rather than *set* on it, which is the
 * question a pupil opening the app the night before is actually asking.
 */
data class DiaryDayUi(
    val date: LocalDate,
    val lessons: List<DiaryLesson>,
    val homework: List<DiaryHomework>,
) {
    /** Whether anything on this day can be corrected at all. @see correctable */
    val correctable: Boolean
        get() = lessons.any { it.correctable } || homework.any { it.correctable }
}

/**
 * Whether this row can carry a correction.
 *
 * The key is the server's to build, and a row that arrived without one — from a
 * server older than the corrections, or one that could not key the row — can
 * never be matched by anything written against it. Drawn as a tap target it
 * would send every tap to a `422`, under a hint telling people to tap.
 */
internal val DiaryLesson.correctable: Boolean get() = target.isNotBlank()

/** @see DiaryLesson.correctable */
internal val DiaryHomework.correctable: Boolean get() = target.isNotBlank()

/**
 * The date it is where the diary is.
 *
 * [zone] is the session's — `DiaryTarget.zoneId()`, which is the zone the server
 * told this phone at registration and cuts the diary's days at. It used to be
 * Moscow for every diary, which was true while the only diary was Petersburg's;
 * a «Сетевой город» pupil in Tomsk would have opened on yesterday for four
 * hours every evening. Not `LocalDate.now()` either: a pupil travelling, or a
 * phone left on another zone, would open on a day the diary has not reached.
 *
 * [DiaryWindows.today] is the rule; this is the name the screens read it by.
 */
fun diaryToday(zone: ZoneId, clock: Clock = Clock.systemUTC()): LocalDate =
    DiaryWindows.today(zone, clock)

/** Monday of the week [date] falls in; the week view's anchor, and the cache's key. */
fun diaryWeekStart(date: LocalDate): LocalDate = DiaryWindows.weekStart(date)

/**
 * Groups a week's worth of lessons and homework into days.
 *
 * Only days that have something are returned. A week view that drew seven
 * cards, five of them empty, would push Friday off the screen to make room for
 * a Sunday nobody has lessons on — and "nothing on Saturday" is already said,
 * once, by Saturday not being there.
 *
 * Anything outside the seven days from [weekStart] is dropped rather than
 * clamped: it can only be there because a response overshot the range it was
 * asked for, and drawing it would put a lesson under the wrong date.
 */
fun diaryWeek(
    weekStart: LocalDate,
    lessons: List<DiaryLesson>,
    homework: List<DiaryHomework>,
): List<DiaryDayUi> {
    val week = (0L until DaysInWeek).map(weekStart::plusDays)
    val lessonsByDate = lessons.groupBy { it.date }
    val homeworkByDate = homework.groupBy { it.dueDate }
    return week.mapNotNull { date ->
        val day = lessonsByDate[date].orEmpty().sortedWith(LessonOrder)
        val due = homeworkByDate[date].orEmpty().sortedBy { it.subject }
        if (day.isEmpty() && due.isEmpty()) null else DiaryDayUi(date, day, due)
    }
}

/**
 * By the bell, then by the lesson number.
 *
 * The number is the fallback rather than the key because the diary numbers
 * lessons per day and is not above numbering two of them the same when a class
 * splits into groups; the clock is what a pupil reads the day by. Lessons with
 * neither sort last, in the order the server sent them.
 */
private val LessonOrder = compareBy<DiaryLesson>(
    { it.startsAt == null && it.number == null },
    { it.startsAt },
    { it.number },
)

/** Days in the week view. The whole week, Monday first, weekend included. */
private const val DaysInWeek = 7L

/**
 * One subject's column of the register.
 *
 * @property average the mean of the numeric marks, or `null` when there are
 *   none. Absences, lateness and remarks are not marks and never move it.
 * @property marks everything the diary has for the subject, in the order it is
 *   drawn: real marks first, then the rest.
 */
data class DiarySubjectMarks(
    val subject: String,
    val marks: List<DiaryMark>,
    val average: Double?,
) {
    /** The marks that are drawn as marks. */
    val grades: List<DiaryMark> get() = marks.filter { it.kind == DiaryMarkKind.GRADE }

    /** Absences, lateness and remarks: drawn as rows that say what they are. */
    val notes: List<DiaryMark> get() = marks.filter { it.kind != DiaryMarkKind.GRADE }
}

/**
 * Groups the register by subject and averages what can be averaged.
 *
 * The average is over [DiaryMark.numericValue], which is `null` for everything
 * that is not a plain number *and* for everything that is not a mark — so a
 * term of "Н", "5-" and "б/о" produces no average rather than a made-up one.
 *
 * Subjects are ordered by name and their entries newest first, because the
 * question a parent opens this screen with is "what happened this week".
 */
fun summariseMarks(marks: List<DiaryMark>): List<DiarySubjectMarks> = marks
    .filter { it.subject.isNotBlank() }
    .groupBy { it.subject }
    .map { (subject, entries) ->
        val ordered = entries.sortedWith(
            compareByDescending<DiaryMark> { it.date ?: LocalDate.MIN }
                .thenBy { it.value },
        )
        val numbers = ordered.mapNotNull { it.numericValue }
        DiarySubjectMarks(
            subject = subject,
            marks = ordered,
            average = if (numbers.isEmpty()) null else numbers.sum().toDouble() / numbers.size,
        )
    }
    .sortedBy { it.subject.lowercase() }

/** What a non-mark entry is called on screen. */
@StringRes
fun DiaryMarkKind.labelRes(): Int = when (this) {
    DiaryMarkKind.GRADE -> R.string.diary_kind_grade
    DiaryMarkKind.ABSENCE -> R.string.diary_kind_absence
    DiaryMarkKind.LATE -> R.string.diary_kind_late
    DiaryMarkKind.REMARK -> R.string.diary_kind_remark
    DiaryMarkKind.OTHER -> R.string.diary_kind_other
}

/**
 * Which accent slot an entry takes.
 *
 * Deliberately not "red for a two": a mark is a fact about a lesson and not a
 * verdict, and an app that paints a child's twos red is an app a child stops
 * opening. The colour separates *kinds* — a mark from an absence from a remark
 * — which is the distinction the screen exists to make.
 */
fun DiaryMarkKind.toneIndex(): Int = when (this) {
    DiaryMarkKind.GRADE -> 0
    DiaryMarkKind.ABSENCE -> 5
    DiaryMarkKind.LATE -> 4
    DiaryMarkKind.REMARK -> 3
    DiaryMarkKind.OTHER -> 2
}

/**
 * The window the marks screen asks for, [from][DiaryDateRange.from] to
 * [to][DiaryDateRange.to] inclusive.
 *
 * The same type the import fills the cache with, because the two have to be
 * the same range: a window worked out twice is a window that will one day be
 * worked out differently, and a cache that is never hit says nothing about it.
 */
typealias DiaryRange = DiaryDateRange

/**
 * The current term, cut to what the server will serve — [DiaryWindows.gradeWindow],
 * which says how. The server refuses anything wider than
 * [DiaryRepository.MAX_RANGE_DAYS], and a school quarter is routinely longer.
 */
fun diaryGradeRange(today: LocalDate, period: DiaryPeriod? = null): DiaryRange =
    DiaryWindows.gradeWindow(today, period)

/**
 * One row's correctable fields, as the sheet needs them.
 *
 * Built per row rather than read out of the row in the sheet, because the sheet
 * has to know three different things about each field and only one of them is
 * on screen: what is being shown, what the diary itself says, and whether the
 * diary has changed since the correction was written. A corrected field shows
 * the correction; the upstream value survives only in [upstream], and that is
 * what a save compares against to decide between writing a correction and
 * taking one off.
 *
 * @property target the key the server filed this row under. Echoed back
 *   untouched — building one here would be a second implementation of a key
 *   that has to match exactly, and the two would agree until the first lesson
 *   with no number.
 * @property ambiguous the server could not tell this row from another on the
 *   same day, so nothing is applied to either. The sheet says so and offers
 *   only the reset.
 */
data class DiaryCorrections(
    val title: String,
    val subtitle: String?,
    val target: String,
    val values: Map<DiaryField, String>,
    val upstream: Map<DiaryField, String?>,
    val corrected: Set<DiaryField>,
    val changedUpstream: Set<DiaryField>,
    val ambiguous: Boolean,
) {
    /** In the order the sheet draws them. */
    val fields: List<DiaryField> get() = values.keys.toList()

    fun upstreamOf(field: DiaryField): String? = upstream[field]

    /**
     * Whether this row has anything to take off.
     *
     * Counted over [corrected] — the corrections this build can **name** — and
     * the «Исправлено» badge is drawn from the same set for the same reason.
     * Drawn from the raw edit list instead, a correction on a field a newer
     * server knows and this build does not would mark the row as corrected and
     * offer nothing to undo it with.
     */
    val hasCorrections: Boolean get() = corrected.isNotEmpty()
}

/**
 * The fields of a lesson that are correctable **here**, in the order they read.
 *
 * `homework` is not among them, though the server accepts it. The lesson row
 * does not draw a lesson's homework — the day card draws the homework list
 * under it, from the other endpoint — so correcting it on the lesson would edit
 * something invisible, and the assignment shown below would keep the diary's
 * own text because it is a different item with a different key. One assignment,
 * one place to correct it.
 */
private val LESSON_FIELDS = listOf(
    DiaryField.ROOM,
    DiaryField.TEACHER,
    DiaryField.TOPIC,
)

internal fun DiaryLesson.corrections(): DiaryCorrections {
    val shown = mapOf(
        DiaryField.ROOM to room.orEmpty(),
        DiaryField.TEACHER to teacher.orEmpty(),
        DiaryField.TOPIC to topic.orEmpty(),
    )
    return build(
        title = subject,
        subtitle = number?.let { "$it" },
        target = target,
        order = LESSON_FIELDS,
        shown = shown,
        edits = edits,
        ambiguous = ambiguous,
    )
}

internal fun DiaryHomework.corrections(): DiaryCorrections = build(
    title = subject,
    subtitle = null,
    target = target,
    order = listOf(DiaryField.TEXT),
    shown = mapOf(DiaryField.TEXT to text),
    edits = edits,
    ambiguous = ambiguous,
)

private fun build(
    title: String,
    subtitle: String?,
    target: String,
    order: List<DiaryField>,
    shown: Map<DiaryField, String>,
    edits: List<DiaryEdit>,
    ambiguous: Boolean,
): DiaryCorrections {
    val byField = edits.mapNotNull { edit ->
        DiaryField.fromWire(edit.field)?.let { field -> field to edit }
    }.toMap()
    return DiaryCorrections(
        title = title,
        subtitle = subtitle,
        target = target,
        // A LinkedHashMap, so `fields` comes back in the order above rather
        // than in whatever order a hash gives.
        values = order.associateWithTo(LinkedHashMap()) { shown[it].orEmpty() },
        // For a corrected field the diary's own answer is only in the edit;
        // for an untouched one what is shown *is* the diary's answer.
        //
        // Blank becomes null on both paths. The edit already reports "the diary
        // said nothing" as null, and a field the diary never filled in has to
        // read the same way — otherwise "empty" means null in one row and "" in
        // the next, and the two comparisons that decide between writing a
        // correction and taking one off would disagree about which is which.
        upstream = order.associateWithTo(LinkedHashMap()) { field ->
            val answer =
                if (field in byField) byField.getValue(field).original else shown[field]
            answer?.takeIf { it.isNotBlank() }
        },
        corrected = byField.keys,
        changedUpstream = byField.filterValues { it.changedUpstream }.keys,
        ambiguous = ambiguous,
    )
}

package com.lumenpearson.lessons.ui.diary

import androidx.annotation.StringRes
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

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
)

/**
 * The zone the diary's own days are cut at.
 *
 * Every other date in this app comes from the class's zone, because the project
 * serves schools across eleven of them. The diary has no class behind it and
 * needs none: it is one city's service, and that city keeps Moscow time. The
 * server cuts the same boundary in `providers/petersburg`, and the two have to
 * agree - a phone that highlighted a different day as today than the server
 * served the week for would look like the diary had lost a day.
 */
val DiaryZone: ZoneId = ZoneId.of("Europe/Moscow")

/**
 * The date it is in the city whose diary this is.
 *
 * Not `LocalDate.now()`: a pupil travelling east, or a phone left on another
 * zone, would otherwise open the diary on a day the diary has not reached.
 */
fun diaryToday(clock: Clock = Clock.systemUTC()): LocalDate =
    LocalDateTime.ofInstant(clock.instant(), DiaryZone).toLocalDate()

/** Monday of the week [date] falls in; the week view's anchor. */
fun diaryWeekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

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
 * The window the marks screen asks for, as [from] to [to] inclusive.
 *
 * The server refuses anything wider than
 * [DiaryRepository.MAX_RANGE_DAYS] with a 422, and a school quarter is
 * routinely longer than that, so the term cannot simply be passed through. The
 * rule, in order:
 *
 *  * end at today, or at the end of the term when the term is already over —
 *    asking for marks that have not been given yet returns nothing and says
 *    nothing;
 *  * start at the beginning of the term, when that fits;
 *  * otherwise start as far back as the limit allows, so what is shown is the
 *    most recent 62 days of the term rather than its first 62.
 */
data class DiaryRange(val from: LocalDate, val to: LocalDate)

/** @see DiaryRange */
fun diaryGradeRange(today: LocalDate, period: DiaryPeriod? = null): DiaryRange {
    val limit = DiaryRepository.MAX_RANGE_DAYS
    val end = period?.endsOn?.takeIf { it.isBefore(today) } ?: today
    val widest = end.minusDays(limit)
    val start = period?.startsOn?.takeIf { it.isAfter(widest) } ?: widest
    // A term whose start is somehow after its end cannot narrow anything.
    return DiaryRange(from = minOf(start, end), to = end)
}

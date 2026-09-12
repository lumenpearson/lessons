package com.lumenpearson.lessons.widget.ui

import android.content.Context
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.ScheduleEngine
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.format.HomeworkDayLabel
import com.lumenpearson.lessons.widget.format.WidgetStrings
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The flattened, already-worded view of a [DayState].
 *
 * Turning the sealed hierarchy into four nullable strings once, outside the
 * composables, means every size class draws the same words from the same code
 * path: TINY and XLARGE cannot disagree about what "сейчас" is, they only
 * disagree about how much of it they have room for.
 *
 * @property label the state word: "Урок", "Перемена", "Столовая"…
 * @property subject what is happening, or what is next during a break.
 * @property detail the line under the subject: where it is and how long it
 *   lasts — "каб. 30 · из 40 мин", "перемена 20 мин". Null on the sizes that
 *   have no room for it, and whenever the timetable knows neither.
 * @property countdown pre-worded, e.g. "осталось 12 мин" or "через 8 мин".
 * @property bareCountdown the same duration without the framing verb, for the
 *   one-line TINY layout where "осталось" does not fit.
 * @property countdownWord what the ticking figure is counting to — "до звонка"
 *   or "до начала". The `Chronometer` can only draw digits, and "05:57" on its
 *   own reads as a time of day rather than as five minutes and change.
 * @property progress 0..1 through the current lesson/break/event, or null when
 *   nothing is running.
 * @property accentIsUrgent true in the last five minutes, which is when a
 *   student actually looks at the widget.
 * @property endsAt when the running lesson, break or event is over, in the
 *   school's wall time. It is what the live countdown counts to, and it is null
 *   exactly when [countdown] is: after school there is nothing to count.
 * @property nextAt when the thing after this one starts, for the "Дальше" line.
 * @property nextSubject what that thing is. Null during a break, where the
 *   subject *is* what comes next and repeating it would be noise.
 */
internal data class Headline(
    val label: String,
    val subject: String?,
    val countdown: String?,
    val bareCountdown: String?,
    val progress: Float?,
    val accentIsUrgent: Boolean,
    val endsAt: java.time.LocalDateTime? = null,
    val detail: String? = null,
    val countdownWord: String? = null,
    val nextAt: java.time.LocalTime? = null,
    val nextSubject: String? = null,
)

/** Minutes below which a countdown is drawn in the accent colour. */
private const val URGENT_MINUTES = 5L

/**
 * Words a [DayState] for the "what is happening now" half of the widget.
 *
 * `AfterSchool` and `DayOff` still get a headline — the widget shows it above
 * the homework block on the larger sizes — but they carry no countdown, because
 * "осталось 14 ч" until Monday is noise, not information.
 */
internal fun headlineOf(context: Context, state: DayState): Headline = when (state) {
    is DayState.BeforeSchool -> Headline(
        label = WidgetStrings.stateLabel(context, state),
        subject = state.next.subject,
        countdown = WidgetStrings.durationIn(context, state.startsIn),
        bareCountdown = WidgetStrings.duration(context, state.startsIn, short = true),
        progress = null,
        accentIsUrgent = state.startsIn.toMinutes() <= URGENT_MINUTES,
        endsAt = state.validUntil,
        detail = lessonDetail(context, state.next),
        countdownWord = WidgetStrings.untilStart(context),
    )

    is DayState.InLesson -> Headline(
        label = WidgetStrings.stateLabel(context, state),
        subject = state.current.subject,
        countdown = WidgetStrings.durationLeft(context, state.endsIn),
        bareCountdown = WidgetStrings.duration(context, state.endsIn, short = true),
        progress = state.progress,
        accentIsUrgent = state.endsIn.toMinutes() <= URGENT_MINUTES,
        endsAt = state.validUntil,
        detail = lessonDetail(context, state.current),
        countdownWord = WidgetStrings.untilBell(context),
        nextAt = state.next?.startsAt,
        nextSubject = state.next?.subject,
    )

    // On a break the useful subject is the one you are walking towards, not the
    // one you just left, so `next` wins over `previous`.
    is DayState.OnBreak -> Headline(
        label = WidgetStrings.stateLabel(context, state),
        subject = state.next.subject,
        countdown = WidgetStrings.durationLeft(context, state.endsIn),
        bareCountdown = WidgetStrings.duration(context, state.endsIn, short = true),
        progress = state.progress,
        accentIsUrgent = state.endsIn.toMinutes() <= URGENT_MINUTES,
        endsAt = state.validUntil,
        // How long the break *is*, not how much of it is left: the figure beside
        // it already says what is left, and twenty minutes and five minutes are
        // two different plans for the same gap.
        detail = WidgetStrings.meta(
            context,
            state.previous?.let {
                WidgetStrings.breakLength(context, minutesBetween(it.endsAt, state.next.startsAt))
            },
            if (state.next.room.isNullOrBlank()) null else WidgetStrings.room(context, state.next),
        ),
        countdownWord = WidgetStrings.untilBell(context),
    )

    is DayState.DuringEvent -> Headline(
        label = WidgetStrings.stateLabel(context, state),
        subject = state.event.title,
        countdown = WidgetStrings.durationLeft(context, state.endsIn),
        bareCountdown = WidgetStrings.duration(context, state.endsIn, short = true),
        progress = state.progress,
        accentIsUrgent = state.endsIn.toMinutes() <= URGENT_MINUTES,
        endsAt = state.validUntil,
        detail = WidgetStrings.meta(
            context,
            state.event.location?.takeIf { it.isNotBlank() },
            WidgetStrings.ofMinutes(
                context,
                minutesBetween(state.event.startsAt, state.event.endsAt),
            ),
        ),
        countdownWord = WidgetStrings.untilBell(context),
        nextAt = state.next?.startsAt,
        nextSubject = state.next?.subject,
    )

    is DayState.AfterSchool,
    is DayState.DayOff,
    is DayState.NoData,
    -> Headline(
        label = WidgetStrings.stateLabel(context, state),
        subject = null,
        countdown = null,
        bareCountdown = null,
        progress = null,
        accentIsUrgent = false,
    )
}

/** "каб. 30 · из 40 мин" — where the lesson is, and how long it runs. */
private fun lessonDetail(context: Context, lesson: Lesson): String? = WidgetStrings.meta(
    context,
    if (lesson.room.isNullOrBlank()) null else WidgetStrings.room(context, lesson),
    WidgetStrings.ofMinutes(context, minutesBetween(lesson.startsAt, lesson.endsAt)),
)

/** Whole minutes between two wall-clock times on the same day. */
private fun minutesBetween(from: java.time.LocalTime, to: java.time.LocalTime): Int =
    java.time.Duration.between(from, to).toMinutes().toInt().coerceAtLeast(0)

/**
 * A one-line plan for a school day: how many lessons and when the first is.
 *
 * Drawn under the timeline on the tallest size, where the rest of today is one
 * row or none by the middle of the afternoon — which is exactly the hour a pupil
 * is deciding what to put in a bag for tomorrow.
 */
internal fun dayPlanOf(context: Context, day: SchoolDay?): String? {
    val lessons = day?.activeLessons.orEmpty()
    val first = lessons.firstOrNull() ?: return null
    return WidgetStrings.dayPlan(context, lessons.size, first.subject, first.startsAt)
}

/**
 * The after-school half of the product: which day the homework is for, and the
 * homework itself.
 *
 * @property header full-width wording, e.g. "Домашнее задание на понедельник".
 * @property shortHeader the "ДЗ на …" form for narrow sizes.
 * @property items homework for that day, already sorted by subject so the list
 *   does not reshuffle between redraws.
 * @property subjectCount worded count for the TINY layout.
 * @property isKnown false when there is no next school day at all (end of the
 *   cached window, or a holiday longer than the sync horizon).
 */
internal data class HomeworkPresentation(
    val header: String,
    val shortHeader: String,
    val items: List<HomeworkItem>,
    val subjectCount: String,
    val isKnown: Boolean,
)

/**
 * True when the homework block *is* the widget, rather than a footnote under a
 * timeline.
 *
 * This is the switch the whole product turns on: the moment the last lesson
 * ends, and all weekend, the widget stops being a clock and becomes a homework
 * reminder.
 */
internal fun isHomeworkPrimary(state: DayState): Boolean =
    state is DayState.AfterSchool || state is DayState.DayOff

/**
 * Builds the homework block for [day].
 *
 * [day] is passed in rather than taken from [DayState.homeworkFocus] because the
 * XLARGE layout shows homework *during* the school day too, and `DayState` only
 * carries a homework day once lessons are over. The caller resolves it as
 * `state.homeworkFocus ?: timetable.schoolDayAfter(today)`.
 *
 * [todayDate] is passed in rather than read from the clock so the whole
 * composable tree stays a pure function of its arguments; it comes from the
 * `now` the state was computed at, which keeps the header and the countdown
 * consistent even if the render straddles midnight.
 */
internal fun homeworkOf(
    context: Context,
    day: SchoolDay?,
    todayDate: LocalDate,
): HomeworkPresentation {
    if (day == null) {
        val unknown = context.getString(R.string.widget_homework_unknown_day)
        return HomeworkPresentation(
            header = unknown,
            shortHeader = unknown,
            items = emptyList(),
            subjectCount = WidgetStrings.subjectCount(context, 0),
            isKnown = false,
        )
    }

    val label = HomeworkDayLabel.of(target = day.date, today = todayDate)
    val items = day.homework
        .filter { it.text.isNotBlank() }
        // Sorted so the list does not reshuffle between redraws; a widget whose
        // rows swap places every minute is unreadable at a glance.
        .sortedBy { it.subject.lowercase() }
    return HomeworkPresentation(
        header = WidgetStrings.homeworkHeader(context, label, short = false),
        shortHeader = WidgetStrings.homeworkHeader(context, label, short = true),
        items = items,
        subjectCount = WidgetStrings.subjectCount(context, subjectsIn(items)),
        isKnown = true,
    )
}

/**
 * How many *subjects* a homework list covers.
 *
 * The word beside this number is "предмет", so the number has to be a count of
 * subjects and not of entries. A teacher who files the reading and the exercises
 * for one lesson separately made the TINY widget say "2 предмета" where the line
 * on the larger sizes — which has always counted distinct subjects — said one,
 * and the two were visible side by side on the same home screen.
 */
internal fun subjectsIn(homework: List<HomeworkItem>): Int = homework
    .filter { it.text.isNotBlank() }
    .distinctBy { it.subject }
    .size

/**
 * The lessons still to come today, in timeline order, for the LARGE/XLARGE
 * timeline and the MEDIUM "next up" column.
 *
 * Uses [com.lumenpearson.lessons.core.model.ScheduleEngine.remainingLessons] so
 * the widget and the app agree on what "remaining" means (a lesson in progress
 * counts as remaining; a cancelled one never does).
 */
internal fun remainingLessonsOf(today: SchoolDay?, now: LocalDateTime): List<Lesson> =
    today?.let { ScheduleEngine.remainingLessons(it, now.toLocalTime()) }.orEmpty()

/**
 * The events still to come today.
 *
 * Events were cached and never drawn anywhere on the widget: a class trip, an
 * exam or a canteen slot existed in the snapshot and the home screen showed the
 * lessons it replaces as if nothing were happening. The filter matches
 * [remainingLessonsOf] — something in progress still counts as remaining,
 * because "сейчас идёт" is the answer the widget exists to give.
 */
internal fun remainingEventsOf(today: SchoolDay?, now: LocalDateTime): List<SchoolEvent> {
    val time = now.toLocalTime()
    return today?.events.orEmpty().filter { it.endsAt > time }.sortedBy { it.startsAt }
}

/**
 * Lessons and events on one axis, in the order they happen.
 *
 * Two lists drawn one after the other would put a trip that replaces the third
 * lesson below the fifth, which is worse than not showing it. Ties go to the
 * event: an event that starts exactly when a lesson does is the thing that
 * replaced it.
 */
internal fun remainingTimeline(today: SchoolDay?, now: LocalDateTime): List<TimelineEntry> {
    val lessons = remainingLessonsOf(today, now).map { TimelineEntry.OfLesson(it) }
    val events = remainingEventsOf(today, now).map { TimelineEntry.OfEvent(it) }
    return (lessons + events).sortedWith(
        compareBy({ it.startsAt }, { if (it is TimelineEntry.OfEvent) 0 else 1 }),
    )
}

/** One row of the merged timeline. */
internal sealed interface TimelineEntry {
    val startsAt: java.time.LocalTime

    @JvmInline
    value class OfLesson(val lesson: Lesson) : TimelineEntry {
        override val startsAt: java.time.LocalTime get() = lesson.startsAt
    }

    @JvmInline
    value class OfEvent(val event: SchoolEvent) : TimelineEntry {
        override val startsAt: java.time.LocalTime get() = event.startsAt
    }
}

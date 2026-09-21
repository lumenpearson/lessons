package com.lumenpearson.lessons.widget.ui

import android.content.Context
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.RibbonEntry
import com.lumenpearson.lessons.core.model.ScheduleEngine
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.ribbonOf
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.format.HomeworkDayLabel
import com.lumenpearson.lessons.widget.format.WidgetStrings
import java.time.Duration
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
 * @property accentIsUrgent true while the printed figure reads five minutes or
 *   fewer, which is when a student actually looks at the widget. Tied to the
 *   figure and not to the duration on purpose; see [isUrgent].
 * @property endsAt when the running lesson, break or event is over, in the
 *   school's wall time. It is what the live countdown counts to. Every state
 *   that sets [countdown] also sets this, because `ScheduleEngine` fills
 *   `validUntil` for all four of them — so the frozen fallback in `Countdown`
 *   cannot fire today. It is kept rather than deleted because `validUntil` is
 *   declared nullable on every `DayState`, so nothing but that one producer
 *   stops a future state from counting something with no end, and drawing
 *   nothing where a number belongs is a worse answer than drawing the frozen
 *   string.
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

/** Minutes at or below which a countdown is drawn in the accent colour. */
private const val URGENT_MINUTES = 5L

/**
 * Whether [remaining] is close enough to colour the countdown.
 *
 * Judged on the figure the widget is about to print, not on the duration behind
 * it. The two round opposite ways — [WidgetStrings.minutesShown] rounds up
 * because that is how a clock is read, `Duration.toMinutes()` truncates — so
 * asking the duration meant that from 5:01 to 5:59 the widget drew «6 мин» in
 * the error colour, one minute outside the five this is documented as.
 */
internal fun isUrgent(remaining: Duration): Boolean =
    WidgetStrings.minutesShown(remaining) <= URGENT_MINUTES

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
        accentIsUrgent = isUrgent(state.startsIn),
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
        accentIsUrgent = isUrgent(state.endsIn),
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
        accentIsUrgent = isUrgent(state.endsIn),
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
        accentIsUrgent = isUrgent(state.endsIn),
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
    Duration.between(from, to).toMinutes().toInt().coerceAtLeast(0)

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
 * The lesson the widget should call «Дальше», according to the state itself.
 *
 * Every state that has an answer already carries one, because [ScheduleEngine]
 * worked it out against the whole day when it built the state — so asking the
 * state is the only way the «Дальше» column and the headline above it can agree.
 * Deriving it again from the remaining lessons is what they used to do, and it
 * disagreed: filtering out "the lesson currently running" only recognises a
 * running lesson under [DayState.InLesson], so during an assembly that replaces
 * the third lesson the third lesson was still listed — at a time already past.
 *
 *  * [DayState.BeforeSchool] — the first lesson of the day.
 *  * [DayState.InLesson] — the lesson after this one, or null in the last.
 *  * [DayState.OnBreak] — the lesson the break leads to.
 *  * [DayState.DuringEvent] — the first lesson starting at or after the event
 *    ends, which is null when the event closes the day and is never the lesson
 *    the event replaced.
 *  * [DayState.AfterSchool], [DayState.DayOff], [DayState.NoData] — null. There
 *    is no next lesson today, and these three do not reach a layout that asks.
 */
internal fun nextLessonOf(state: DayState): Lesson? = when (state) {
    is DayState.BeforeSchool -> state.next
    is DayState.InLesson -> state.next
    is DayState.OnBreak -> state.next
    is DayState.DuringEvent -> state.next
    is DayState.AfterSchool -> null
    is DayState.DayOff -> null
    is DayState.NoData -> null
}

/**
 * The lessons to list under «Дальше»: [nextLessonOf] and everything behind it.
 *
 * Anchored on the state's own answer rather than filtered by identity. Anything
 * starting before that lesson is either finished, running, or replaced by the
 * event on the screen above — and none of the three belongs under a heading
 * that says "next".
 */
internal fun upcomingLessons(
    state: DayState,
    today: SchoolDay?,
    now: LocalDateTime,
    limit: Int,
): List<Lesson> {
    val next = nextLessonOf(state) ?: return emptyList()
    return remainingLessonsOf(today, now)
        .filter { it.startsAt >= next.startsAt }
        .take(limit)
}

/**
 * Lessons and events on one axis, in the order they happen.
 *
 * Two lists drawn one after the other would put a trip that replaces the third
 * lesson below the fifth, which is worse than not showing it. Ties go to the
 * event: an event that starts exactly when a lesson does is the thing that
 * replaced it.
 *
 * The merge and that tie-break used to live here, in a copy nothing tested —
 * the rule existed only in the comment above it. They are [ribbonOf]'s now, in
 * `:core:model`, where the day screen asks the same question of the same day
 * and `DayRibbonTest` holds the answer. What is left here is the widget's own
 * half: it wants what is still to come, and it never wants the breaks.
 */
internal fun remainingTimeline(today: SchoolDay?, now: LocalDateTime): List<RibbonEntry> =
    ribbonOf(today).remaining(now.toLocalTime())

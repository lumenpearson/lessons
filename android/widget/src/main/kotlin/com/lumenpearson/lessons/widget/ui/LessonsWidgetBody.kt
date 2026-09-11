package com.lumenpearson.lessons.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.WidgetOptions
import com.lumenpearson.lessons.widget.WidgetSizeClass
import com.lumenpearson.lessons.widget.format.WidgetStrings
import com.lumenpearson.lessons.widget.format.ellipsize
import java.time.LocalDateTime

/**
 * Corner radius of the widget surface.
 *
 * 24 dp, the same radius `RoundedCardContainer` gives a group of rows in the
 * app, so the widget reads as one more block of the same design system. It also
 * sits close to the launcher's own widget rounding on API 31+; below that
 * `cornerRadius` is a no-op and the launcher supplies square edges, which is
 * what pre-Material-You launchers draw anyway.
 */
private val SURFACE_CORNER = 24.dp

/** Width of the MEDIUM layout's right-hand "Дальше" column. */
private val NEXT_UP_COLUMN = 118.dp

/**
 * How many children a Glance container may hold before the rest are discarded.
 *
 * Not a guideline — a hard truncation. Glance builds a widget out of pre-baked
 * `RemoteViews` layouts, and it ships variants for zero through ten children;
 * its translator takes the first ten of whatever it is given and drops the
 * remainder without a warning, an exception or a log line.
 *
 * The cost of not knowing this was severe and invisible: the 4x4 layout emitted
 * eighteen siblings, so it showed one lesson of the five it advertises, and the
 * 5x5's homework block — children twenty-nine and thirty — never rendered at
 * all. Every variable-length list here is therefore wrapped in its own
 * container, and every take() is clamped.
 */
private const val CHILD_LIMIT = 10

/**
 * The entire widget, as a pure function of its arguments.
 *
 * Nothing in this tree reads a clock, a repository, or a `SharedPreferences`.
 * Every value it needs was resolved in
 * [com.lumenpearson.lessons.widget.LessonsWidget.provideGlance] before
 * `provideContent` was called, which is what makes the widget cheap to redraw
 * (no suspending work inside a recomposition that runs on every alarm) and
 * possible to reason about (the same six arguments always draw the same pixels).
 *
 * @param state what is happening now, or null when there is no cached timetable.
 * @param signedIn whether a class has been joined. Only this tells the two
 *   causes of a null [state] apart: no class code yet, or a class joined whose
 *   timetable has never reached the device.
 * @param today today's [SchoolDay], for the remaining-day timeline. Null on a
 *   date outside the cached window.
 * @param homeworkDay the day whose homework to show. After school this is
 *   [com.lumenpearson.lessons.core.model.homeworkFocus]; during the day the
 *   caller resolves the next school day so XLARGE can show homework alongside
 *   the timeline. `DayState` alone cannot supply this, which is why it is a
 *   separate argument rather than being derived here.
 * @param now the instant [state] was computed at. Passed in, not read, so the
 *   headline, the timeline and the homework header can never disagree about what
 *   day it is.
 * @param size which of the five layouts to draw.
 * @param options the handful of user settings that change what is drawn.
 * @param onClick where a tap goes — always the app's main activity.
 */
@Composable
internal fun LessonsWidgetBody(
    state: DayState?,
    signedIn: Boolean,
    today: SchoolDay?,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
    options: WidgetOptions,
    onClick: Action,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            // appWidgetBackground() is what lets the launcher clip and animate the
            // widget as one surface; without it the rounded corners are ours alone
            // and the drop animation looks wrong.
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(SURFACE_CORNER)
            .clickable(onClick)
            .padding(size.paddingDp.dp),
    ) {
        if (state == null) {
            EmptyBody(size = size, signedIn = signedIn)
        } else {
            when (size) {
                WidgetSizeClass.TINY -> TinyBody(state, homeworkDay, now, size)
                WidgetSizeClass.WIDE -> WideBody(state, homeworkDay, now, size)
                WidgetSizeClass.SMALL -> SmallBody(state, homeworkDay, now, size, options)
                WidgetSizeClass.MEDIUM, WidgetSizeClass.MEDIUM_TALL ->
                    MediumBody(state, today, homeworkDay, now, size, options)

                WidgetSizeClass.LARGE ->
                    TimelineBody(state, today, homeworkDay, now, size, options, withHomework = false)

                WidgetSizeClass.XLARGE, WidgetSizeClass.TALL ->
                    TimelineBody(state, today, homeworkDay, now, size, options, withHomework = true)
            }
        }
    }
}

/**
 * What the widget says when it has no timetable to draw.
 *
 * Deliberately an instruction and not an error: the widget is often the first
 * thing a parent adds after installing, and the whole surface is already
 * clickable, so tapping the sentence does the thing the sentence asks for.
 *
 * Which instruction depends on [signedIn]. Telling somebody who has already
 * joined a class to go and enter a class code sends them to the one screen that
 * cannot help them — what they actually need is to pull the timetable down, or
 * to check the server address.
 */
@Composable
private fun EmptyBody(size: WidgetSizeClass, signedIn: Boolean) {
    val context = LocalContext.current
    val compact = size == WidgetSizeClass.TINY ||
        size == WidgetSizeClass.WIDE ||
        size == WidgetSizeClass.SMALL
    val text = when {
        !signedIn && compact -> R.string.widget_empty_short
        !signedIn -> R.string.widget_empty_title
        compact -> R.string.widget_no_data_short
        else -> R.string.widget_no_data_title
    }
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        BodyText(
            text = context.getString(text),
            size = size,
            maxLines = if (compact) 2 else 3,
            muted = true,
        )
    }
}

/**
 * 2x1: one line, two words.
 *
 * During the day that is the state and the countdown ("ПЕРЕМЕНА 7 мин"); after
 * it, the homework day and how many subjects it has ("ДЗ на завтра 5
 * предметов"), which is enough to decide whether the evening is busy.
 */
@Composable
private fun TinyBody(
    state: DayState,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (isHomeworkPrimary(state)) {
            val homework = homeworkOf(context, homeworkDay, now.toLocalDate())
            StateLabel(
                text = homework.shortHeader,
                size = size,
                uppercase = false,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (homework.isKnown) {
                HSpace(6)
                CaptionText(text = homework.subjectCount, size = size, emphasised = true)
            }
        } else {
            val headline = headlineOf(context, state)
            StateLabel(
                text = headline.label,
                size = size,
                urgent = headline.accentIsUrgent,
                modifier = GlanceModifier.defaultWeight(),
            )
            val trailing = headline.bareCountdown ?: headline.subject?.ellipsize(12)
            if (trailing != null) {
                HSpace(6)
                CaptionText(text = trailing, size = size, emphasised = true)
            }
        }
    }
}

/**
 * 4x1: the whole state on one line, with a countdown that ticks.
 *
 * The width [TINY] does not have is spent on the subject — "ПЕРЕМЕНА · Физика ·
 * 07:42" answers the question without opening anything, and it is the shape a
 * widget one cell high can actually hold. Before this size existed, a widget
 * this shape drew the 110 dp layout and left two thirds of itself empty.
 */
@Composable
private fun WideBody(
    state: DayState,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (isHomeworkPrimary(state)) {
            val homework = homeworkOf(context, homeworkDay, now.toLocalDate())
            StateLabel(
                text = homework.shortHeader,
                size = size,
                uppercase = false,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (homework.isKnown) {
                HSpace(8)
                CaptionText(text = homework.subjectCount, size = size, emphasised = true)
            }
            return@Row
        }

        val headline = headlineOf(context, state)
        StateLabel(text = headline.label, size = size, urgent = headline.accentIsUrgent)
        headline.subject?.let {
            HSpace(8)
            SubjectText(
                text = it,
                size = size,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        Countdown(headline = headline, now = now, size = size)
    }
}

/**
 * 2x2: the state, what it is about, how long is left, and a progress bar.
 *
 * This is the size most people keep, so it is the one tuned hardest: two lines
 * for the subject (Russian subject names are long — "Изобразительное искусство"
 * does not fit on one), one for the countdown, and the bar last because it is
 * the only element that stays readable when it is the thing that gets clipped.
 */
@Composable
private fun SmallBody(
    state: DayState,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
    options: WidgetOptions,
) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (isHomeworkPrimary(state)) {
            HomeworkBlock(
                homework = homeworkOf(context, homeworkDay, now.toLocalDate()),
                size = size,
                maxItems = size.homeworkItems,
                shortHeader = true,
            )
            return@Column
        }

        val headline = headlineOf(context, state)
        StateLabel(text = headline.label, size = size, urgent = headline.accentIsUrgent)
        VSpace(2)
        headline.subject?.let { SubjectText(text = it, size = size, maxLines = 2) }
        VSpace(2)
        Countdown(headline = headline, now = now, size = size, verbose = true)
        if (options.showProgress && size.showsProgressBar && headline.progress != null) {
            VSpace(6)
            StateProgress(progress = headline.progress)
        }
    }
}

/**
 * 4x2: the SMALL layout plus a right-hand column with what comes after this.
 *
 * "Дальше" is the second question every student asks after "how long left", so
 * it earns the extra two columns of width before anything else does.
 */
@Composable
private fun MediumBody(
    state: DayState,
    today: SchoolDay?,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
    options: WidgetOptions,
) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (isHomeworkPrimary(state)) {
            HomeworkBlock(
                homework = homeworkOf(context, homeworkDay, now.toLocalDate()),
                size = size,
                maxItems = size.homeworkItems,
                shortHeader = false,
            )
            return@Column
        }

        val headline = headlineOf(context, state)
        Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                StateLabel(text = headline.label, size = size, urgent = headline.accentIsUrgent)
                VSpace(2)
                headline.subject?.let { SubjectText(text = it, size = size, maxLines = 1) }
                VSpace(2)
                Countdown(headline = headline, now = now, size = size, verbose = true)
            }
            val upcoming = upcomingLessons(state, today, now, limit = size.timelineRows)
            if (upcoming.isNotEmpty()) {
                HSpace(10)
                Column(modifier = GlanceModifier.width(NEXT_UP_COLUMN)) {
                    SectionTitle(text = context.getString(R.string.widget_next_up), size = size)
                    VSpace(4)
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        upcoming.take(CHILD_LIMIT).forEach { lesson ->
                            BodyText(
                                text = "${WidgetStrings.time(lesson.startsAt)}  ${lesson.subject.ellipsize(11)}",
                                size = size,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        if (options.showProgress && size.showsProgressBar && headline.progress != null) {
            VSpace(6)
            StateProgress(progress = headline.progress)
        }
    }
}

/**
 * 4x4 and 5x5: headline, progress, and the whole rest of the school day.
 *
 * The two sizes share one implementation because they differ only in how much
 * they can hold: [withHomework] adds the homework block that makes XLARGE the
 * "everything at once" size. Splitting them into two near-identical composables
 * would guarantee they drift apart.
 */
@Composable
private fun TimelineBody(
    state: DayState,
    today: SchoolDay?,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
    options: WidgetOptions,
    withHomework: Boolean,
) {
    val context = LocalContext.current
    val homework = homeworkOf(context, homeworkDay, now.toLocalDate())

    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (isHomeworkPrimary(state)) {
            // Nothing is running and nothing is left today, so the homework block
            // is not a footnote — it is the widget.
            val headline = headlineOf(context, state)
            StateLabel(text = headline.label, size = size)
            (state as? DayState.DayOff)?.note?.takeIf { it.isNotBlank() }?.let { note ->
                VSpace(2)
                CaptionText(text = note.ellipsize(size.homeworkChars), size = size)
            }
            VSpace(8)
            HomeworkBlock(
                homework = homework,
                size = size,
                maxItems = size.homeworkItems,
                shortHeader = false,
                itemMaxLines = 2,
            )
            return@Column
        }

        val headline = headlineOf(context, state)
        // One child of the outer Column, not five. See CHILD_LIMIT.
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                StateLabel(
                    text = headline.label,
                    size = size,
                    urgent = headline.accentIsUrgent,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Countdown(headline = headline, now = now, size = size)
            }
            VSpace(2)
            headline.subject?.let { SubjectText(text = it, size = size, maxLines = 1) }
            if (options.showProgress && size.showsProgressBar && headline.progress != null) {
                VSpace(6)
                StateProgress(progress = headline.progress)
            }
        }

        VSpace(10)

        // …and one for the whole timeline, however many lessons are left.
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            SectionTitle(text = context.getString(R.string.widget_timeline_title), size = size)
            VSpace(4)
            // Lessons and events on one axis. Drawn as two lists one after the
            // other, a trip that replaces the third lesson appeared below the
            // fifth, which is worse than not showing it at all.
            val remaining = remainingTimeline(today, now)
            if (remaining.isEmpty()) {
                BodyText(
                    text = context.getString(R.string.widget_nothing_left),
                    size = size,
                    muted = true,
                )
            } else {
                val current = (state as? DayState.InLesson)?.current
                // The rows carry their own vertical padding, so there is no
                // spacer between them to spend a child slot on.
                remaining.take(size.timelineRows.coerceAtMost(CHILD_LIMIT - 2)).forEach { entry ->
                    when (entry) {
                        is TimelineEntry.OfLesson -> TimelineRow(
                            lesson = entry.lesson,
                            size = size,
                            options = options,
                            isCurrent = entry.lesson == current,
                        )

                        is TimelineEntry.OfEvent -> EventRow(event = entry.event, size = size)
                    }
                }
            }
        }

        if (withHomework) {
            VSpace(8)
            // A container rather than a rule: in this design language two blocks
            // are separated by grouping one of them, never by drawing a line
            // between them.
            WidgetCard {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    HomeworkBlock(
                        homework = homework,
                        size = size,
                        // The timeline already took most of the height, so the
                        // homework block gets a smaller slice than it would as
                        // the primary content.
                        maxItems = (size.homeworkItems - 2).coerceAtLeast(1),
                        shortHeader = false,
                    )
                }
            }
        }
    }
}

/**
 * How long is left, ticking.
 *
 * The widget is otherwise a snapshot: the process wakes on an alarm, renders,
 * and sleeps, so "осталось 12 мин" stayed at twelve until the next wake — which
 * is exactly wrong for the one number on the screen that a pupil watches. The
 * remaining time is now drawn by a `Chronometer` counting down in the launcher's
 * own process, second by second, costing nothing.
 *
 * The wording around the figure ("осталось …", "через …") is dropped in the
 * live form rather than split into a second view: which of the two it is, is
 * already said by the state label right next to it, and languages disagree about
 * whether the word goes before the number or after it.
 *
 * Falls back to the frozen string when the state has no end — after school there
 * is nothing to count to, and a chronometer counting to nothing shows zero.
 */
@Composable
private fun Countdown(
    headline: Headline,
    now: LocalDateTime,
    size: WidgetSizeClass,
    verbose: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    val endsAt = headline.endsAt
    if (endsAt == null) {
        val frozen = if (verbose) headline.countdown else headline.bareCountdown
        frozen?.let { CaptionText(text = it, size = size, emphasised = true, modifier = modifier) }
        return
    }

    LiveCountdown(
        endsAt = endsAt,
        now = now,
        size = size,
        urgent = headline.accentIsUrgent,
        modifier = modifier,
    )
}

/**
 * Header plus homework lines, or an honest "Ничего не задано".
 *
 * @param maxItems how many subjects this size can show. Anything beyond it is
 *   dropped rather than squeezed: a clipped tenth row helps nobody, and the
 *   widget is a pointer into the app, not a replacement for it.
 */
@Composable
private fun HomeworkBlock(
    homework: HomeworkPresentation,
    size: WidgetSizeClass,
    maxItems: Int,
    shortHeader: Boolean,
    itemMaxLines: Int = 1,
) {
    val context = LocalContext.current
    // Wrapped, so this contributes exactly one child to whatever contains it.
    // Emitted as loose siblings it was the block that pushed the 4x4 and 5x5
    // layouts past CHILD_LIMIT, and it is itself the part that got dropped.
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        CaptionText(
            text = if (shortHeader) homework.shortHeader else homework.header,
            size = size,
            emphasised = true,
            maxLines = 2,
        )
        VSpace(4)
        when {
            !homework.isKnown -> Unit // The header already says the day is unknown.
            homework.items.isEmpty() -> BodyText(
                text = context.getString(R.string.widget_homework_empty),
                size = size,
                muted = true,
            )

            else -> Column(modifier = GlanceModifier.fillMaxWidth()) {
                homework.items.take(maxItems.coerceAtMost(CHILD_LIMIT)).forEach { item ->
                    HomeworkRow(
                        subject = item.subject,
                        text = item.text,
                        size = size,
                        maxLines = itemMaxLines,
                    )
                }
            }
        }
    }
}

/**
 * The lessons to list under "Дальше": everything still to come, minus the one
 * currently running (which is already the headline).
 */
private fun upcomingLessons(
    state: DayState,
    today: SchoolDay?,
    now: LocalDateTime,
    limit: Int,
): List<Lesson> {
    val current = (state as? DayState.InLesson)?.current
    return remainingLessonsOf(today, now)
        .filter { it != current }
        .take(limit)
}

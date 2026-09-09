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
 * Matches the launcher's own widget rounding on API 31+; below that
 * `cornerRadius` is a no-op and the launcher supplies square edges, which is
 * what pre-Material-You launchers draw anyway.
 */
private val SURFACE_CORNER = 20.dp

/** Width of the MEDIUM layout's right-hand "Дальше" column. */
private val NEXT_UP_COLUMN = 118.dp

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
 * @param state what is happening now, or null when there is no timetable at all
 *   — i.e. the user has not entered a class code yet.
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
            NotSignedInBody(size)
        } else {
            when (size) {
                WidgetSizeClass.TINY -> TinyBody(state, homeworkDay, now, size)
                WidgetSizeClass.SMALL -> SmallBody(state, homeworkDay, now, size, options)
                WidgetSizeClass.MEDIUM ->
                    MediumBody(state, today, homeworkDay, now, size, options)

                WidgetSizeClass.LARGE ->
                    TimelineBody(state, today, homeworkDay, now, size, options, withHomework = false)

                WidgetSizeClass.XLARGE ->
                    TimelineBody(state, today, homeworkDay, now, size, options, withHomework = true)
            }
        }
    }
}

/**
 * What the widget says before the user has ever signed in.
 *
 * Deliberately an instruction and not an error: the widget is often the first
 * thing a parent adds after installing, and "Откройте приложение и введите код
 * класса" tells them exactly what to do. The whole surface is already clickable,
 * so tapping the sentence does the thing the sentence asks for.
 */
@Composable
private fun NotSignedInBody(size: WidgetSizeClass) {
    val context = LocalContext.current
    val compact = size == WidgetSizeClass.TINY || size == WidgetSizeClass.SMALL
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        BodyText(
            text = context.getString(
                if (compact) R.string.widget_empty_short else R.string.widget_empty_title,
            ),
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
        headline.countdown?.let { CaptionText(text = it, size = size, emphasised = true) }
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
                headline.countdown?.let { CaptionText(text = it, size = size, emphasised = true) }
            }
            val upcoming = upcomingLessons(state, today, now, limit = size.timelineRows)
            if (upcoming.isNotEmpty()) {
                HSpace(10)
                Column(modifier = GlanceModifier.width(NEXT_UP_COLUMN)) {
                    SectionTitle(text = context.getString(R.string.widget_next_up), size = size)
                    VSpace(4)
                    upcoming.forEach { lesson ->
                        BodyText(
                            text = "${WidgetStrings.time(lesson.startsAt)}  ${lesson.subject.ellipsize(11)}",
                            size = size,
                            maxLines = 1,
                        )
                        VSpace(2)
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
            headline.bareCountdown?.let {
                CaptionText(text = it, size = size, emphasised = true)
            }
        }
        VSpace(2)
        headline.subject?.let { SubjectText(text = it, size = size, maxLines = 1) }
        if (options.showProgress && size.showsProgressBar && headline.progress != null) {
            VSpace(6)
            StateProgress(progress = headline.progress)
        }

        VSpace(10)
        SectionTitle(text = context.getString(R.string.widget_timeline_title), size = size)
        VSpace(4)
        val remaining = remainingLessonsOf(today, now)
        if (remaining.isEmpty()) {
            BodyText(
                text = context.getString(R.string.widget_nothing_left),
                size = size,
                muted = true,
            )
        } else {
            val current = (state as? DayState.InLesson)?.current
            remaining.take(size.timelineRows).forEach { lesson ->
                TimelineRow(
                    lesson = lesson,
                    size = size,
                    options = options,
                    isCurrent = lesson == current,
                )
                VSpace(4)
            }
        }

        if (withHomework) {
            VSpace(8)
            ThinDivider()
            VSpace(8)
            HomeworkBlock(
                homework = homework,
                size = size,
                // The timeline already took most of the height, so the homework
                // block gets a smaller slice than it would as the primary content.
                maxItems = (size.homeworkItems - 2).coerceAtLeast(1),
                shortHeader = false,
            )
        }
    }
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

        else -> homework.items.take(maxItems).forEach { item ->
            HomeworkRow(
                subject = item.subject,
                text = item.text,
                size = size,
                maxLines = itemMaxLines,
            )
            VSpace(2)
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

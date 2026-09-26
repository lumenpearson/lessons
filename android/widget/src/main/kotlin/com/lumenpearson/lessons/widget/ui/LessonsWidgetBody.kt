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
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.RibbonEntry
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.WidgetOptions
import com.lumenpearson.lessons.widget.WidgetSizeClass
import com.lumenpearson.lessons.widget.format.WidgetStrings
import com.lumenpearson.lessons.widget.format.ellipsize
import java.time.LocalDate
import java.time.LocalDateTime

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
 * container, every `take()` is clamped, and the stacked layout below is built
 * from four blocks rather than from a dozen loose lines: spacers are children
 * too, and a spacer between every line is what pushes a long layout over.
 */
internal const val CHILD_LIMIT = 10

/**
 * Below this many rows left today, the widget starts showing tomorrow.
 *
 * By the middle of the afternoon "осталось сегодня" is one line or none, and a
 * tall widget with one line in it is mostly background — at exactly the hour
 * somebody is deciding what to put in a bag for the morning.
 */
private const val LOOK_AHEAD_BELOW_ROWS = 2

/**
 * The entire widget, as a pure function of its arguments.
 *
 * Nothing in this tree reads a clock, a repository, or a `SharedPreferences`.
 * Every value it needs was resolved in
 * [com.lumenpearson.lessons.widget.LessonsWidget.provideGlance] before
 * `provideContent` was called, which is what makes the widget cheap to redraw
 * (no suspending work inside a recomposition that runs on every alarm) and
 * possible to reason about (the same arguments always draw the same pixels).
 *
 * @param state what is happening now, or null when there is no cached timetable.
 * @param mode how this phone came in, if it has. Only this tells the three
 *   causes of a null [state] apart: no way in taken yet, a class joined whose
 *   timetable has never reached the device, or a phone that reads its own
 *   school's diary and has no class timetable to draw.
 * @param today today's [SchoolDay], for the remaining-day timeline and for
 *   today's own homework. Null on a date outside the cached window.
 * @param homeworkDay the day whose homework to show. After school this is
 *   [com.lumenpearson.lessons.core.model.homeworkFocus]; during the day the
 *   caller resolves the next school day so the larger sizes can show homework
 *   alongside the timeline.
 * @param now the instant [state] was computed at. Passed in, not read, so the
 *   headline, the timeline and the homework header can never disagree about what
 *   day it is.
 * @param size which layout to draw, and how much of each thing it can hold.
 * @param options the handful of user settings that change what is drawn.
 * @param onClick where a tap goes — always the app's main activity.
 */
@Composable
internal fun LessonsWidgetBody(
    state: DayState?,
    mode: ShellMode,
    today: SchoolDay?,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
    options: WidgetOptions,
    onClick: Action,
    week: List<DayLoad> = emptyList(),
    onDayClick: ((LocalDate) -> Action)? = null,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            // appWidgetBackground() is what lets the launcher clip and animate the
            // widget as one surface; without it the rounded corners are ours alone
            // and the drop animation looks wrong.
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(WidgetSurfaceCorner)
            .clickable(onClick)
            .padding(size.paddingDp.dp),
    ) {
        // NoData is the same absence a null state is — there is no cached day
        // for this date at all — and every layout below reads absence as
        // emptiness: the timeline prints «Уроков больше нет» because it has no
        // lessons, and the homework line «Ничего не задано» because it has no
        // homework. Both are claims about a day the widget has never seen. The
        // instruction underneath is the only thing it actually knows.
        if (state == null || state is DayState.NoData) {
            EmptyBody(size = size, mode = mode)
        } else {
            when (size) {
                WidgetSizeClass.TINY -> TinyBody(state, homeworkDay, now, size)
                WidgetSizeClass.WIDE -> WideBody(state, homeworkDay, now, size)
                WidgetSizeClass.MEDIUM -> MediumBody(state, today, homeworkDay, now, size, options)
                else -> StackBody(
                    state = state,
                    today = today,
                    homeworkDay = homeworkDay,
                    now = now,
                    size = size,
                    options = options,
                    week = week,
                    onDayClick = onDayClick,
                )
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
 * Which instruction is [emptyTextRes]'s to decide.
 */
@Composable
private fun EmptyBody(size: WidgetSizeClass, mode: ShellMode) {
    val context = LocalContext.current
    val compact = emptyTextIsCompact(size)
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        BodyText(
            text = context.getString(emptyTextRes(mode, size)),
            size = size,
            maxLines = if (compact) 2 else 3,
            muted = true,
        )
    }
}

/**
 * Whether [size] gets the short empty sentence.
 *
 * By width, not by naming the sizes one at a time. The list was written when
 * there were three narrow rungs and did not grow when two more arrived, so
 * SMALL_TALL and NARROW were handed the long strings in a 110 dp column — and
 * Glance text cannot ellipsize, so they were hard-clipped mid-word.
 */
internal fun emptyTextIsCompact(size: WidgetSizeClass): Boolean =
    size.isNarrow || size == WidgetSizeClass.WIDE

/**
 * The sentence the widget draws in place of a timetable, per way in.
 *
 *  - [ShellMode.NONE]: nobody has come in, and there are two ways to — a class
 *    code, or the family's own school — so the sentence names the app rather
 *    than one of them. It used to say «введите код класса», which is wrong for
 *    exactly the family the second way was built for.
 *  - [ShellMode.CLASS]: a class was joined and nothing has synced. Telling
 *    them to enter a class code sends them to the one screen that cannot help;
 *    what they need is to pull the timetable down, or to check the server.
 *  - [ShellMode.DIARY]: the phone reads its own diary and has no class. The
 *    widget draws a class timetable and nothing from the diary (#142), so it
 *    says where the diary is instead of pretending to be about to load one —
 *    «потяните вниз» would promise a timetable that no pull will ever bring.
 */
internal fun emptyTextRes(mode: ShellMode, size: WidgetSizeClass): Int {
    val compact = emptyTextIsCompact(size)
    return when (mode) {
        ShellMode.NONE -> if (compact) R.string.widget_empty_short else R.string.widget_empty_title
        ShellMode.CLASS -> if (compact) R.string.widget_no_data_short else R.string.widget_no_data_title
        ShellMode.DIARY -> if (compact) R.string.widget_diary_only_short else R.string.widget_diary_only_title
    }
}

/**
 * 2x1: one line, two words.
 *
 * During the day that is the state and the countdown ("ПЕРЕМЕНА 7 мин"); after
 * it, the homework day and how many subjects it has ("ДЗ на завтра 5
 * предметов"), which is enough to decide whether the evening is busy.
 *
 * The frozen wording rather than the ticking one: a `Chronometer` shows `MM:SS`,
 * and at this width there is no room for the word that would stop those digits
 * reading as a time of day.
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
 * The width [WidgetSizeClass.TINY] does not have is spent on the subject —
 * "ПЕРЕМЕНА · Физика · 07:42" answers the question without opening anything.
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
        HSpace(8)
        Countdown(headline = headline, now = now, size = size)
    }
}

/**
 * 4x2: the state on the left, what comes after it on the right.
 *
 * The one layout that is two columns rather than a stack. "Дальше" is the second
 * question every student asks after "how long left", and at this height the only
 * place to put an answer is beside the first one.
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
                Countdown(headline = headline, now = now, size = size, labelled = true)
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
 * Every stacked size, from the 2x2 square to half a home screen.
 *
 * One composable rather than six near-identical ones. What changes between them
 * is not the shape but the *portion*: which of the four blocks below a class can
 * afford, and how many rows each of them gets. Those are declared on
 * [WidgetSizeClass] itself, so adding a rung to the ladder is a row of flags
 * rather than another copy of this function drifting away from the rest.
 *
 * The four blocks, in the order a pupil asks for them:
 *
 *  1. **now** — what is running, how long it lasts, how much is left, and how
 *     far through it is;
 *  2. **next** — the lesson after this one, and the week;
 *  3. **today** — everything still to come, and what was set for today;
 *  4. **ahead** — homework for the next school day, and what that day looks
 *     like.
 *
 * Four children plus three spacers is seven, which is under [CHILD_LIMIT] with
 * room to spare. Emitted as loose lines it would be nearer twenty, and Glance
 * would silently draw the first ten.
 */
@Composable
private fun StackBody(
    state: DayState,
    today: SchoolDay?,
    homeworkDay: SchoolDay?,
    now: LocalDateTime,
    size: WidgetSizeClass,
    options: WidgetOptions,
    week: List<DayLoad>,
    onDayClick: ((LocalDate) -> Action)?,
) {
    val context = LocalContext.current
    val headline = headlineOf(context, state)
    val nextDayHomework = homeworkOf(context, homeworkDay, now.toLocalDate())

    // Nothing is running and nothing is left today, so the homework block is not
    // a footnote — it is the widget.
    if (isHomeworkPrimary(state)) {
        RestDayBody(
            state = state,
            homework = nextDayHomework,
            nextDay = homeworkDay,
            size = size,
        )
        return
    }

    val remaining = remainingTimeline(today, now)
    // Tomorrow earns its place when today has almost nothing left in it. Gated on
    // the week strip rather than on the homework block, because the week strip is
    // the flag that means "this size has height to spare" — and the sizes that
    // have it are exactly the ones left looking empty at five in the afternoon.
    val looksAhead = size.showsNextDay ||
        (size.showsWeekStrip && remaining.size <= LOOK_AHEAD_BELOW_ROWS)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        NowBlock(headline = headline, size = size, options = options, now = now)

        if (size.showsNextUp && headline.nextSubject != null && headline.nextAt != null) {
            VSpace(8)
            NextUpLine(at = headline.nextAt, subject = headline.nextSubject, size = size)
        }

        if (size.showsWeekStrip && onDayClick != null && week.isNotEmpty()) {
            VSpace(10)
            WeekStrip(
                week = week,
                today = now.toLocalDate(),
                size = size,
                openDay = onDayClick,
            )
        }

        if (size.timelineRows > 0) {
            VSpace(10)
            TodayBlock(
                remaining = remaining,
                current = (state as? DayState.InLesson)?.current,
                today = today,
                size = size,
                options = options,
            )
        } else if (size.showsTodayHomework) {
            VSpace(8)
            TodayHomeworkLine(today = today, size = size)
        }

        if (size.showsHomework || looksAhead) {
            VSpace(8)
            AheadBlock(
                homework = nextDayHomework,
                nextDay = homeworkDay,
                size = size,
                withPlan = looksAhead,
            )
        }
    }
}

/**
 * Block one: what is happening, in as much detail as the size allows.
 *
 * The countdown sits beside the state word rather than under it, because the two
 * are one sentence — "ПЕРЕМЕНА · до звонка 05:57" — and because on the narrow
 * sizes there is no second line to put it on.
 */
@Composable
private fun NowBlock(
    headline: Headline,
    size: WidgetSizeClass,
    options: WidgetOptions,
    now: LocalDateTime,
) {
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
            HSpace(6)
            Countdown(
                headline = headline,
                now = now,
                size = size,
                // The word only fits once the widget is four cells wide; on a
                // narrow column the state label two lines up carries the sense.
                labelled = !size.isNarrow,
            )
        }

        if (size.showsSubject) {
            VSpace(2)
            headline.subject?.let {
                SubjectText(text = it, size = size, maxLines = if (size.isNarrow) 2 else 1)
            }
        }

        if (size.showsMeta) {
            headline.detail?.let {
                VSpace(2)
                CaptionText(text = it.ellipsize(size.homeworkChars.coerceAtLeast(20)), size = size)
            }
        }

        if (options.showProgress && size.showsProgressBar && headline.progress != null) {
            VSpace(6)
            StateProgress(progress = headline.progress)
        }
    }
}

/**
 * Block three: the rest of the school day, and what was set for it.
 *
 * Lessons and events on one axis — drawn as two lists one after the other, a
 * trip that replaces the third lesson appeared below the fifth, which is worse
 * than not showing it at all.
 */
@Composable
private fun TodayBlock(
    remaining: List<RibbonEntry>,
    current: Lesson?,
    today: SchoolDay?,
    size: WidgetSizeClass,
    options: WidgetOptions,
) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        SectionTitle(text = context.getString(R.string.widget_timeline_title), size = size)
        VSpace(4)
        if (remaining.isEmpty()) {
            BodyText(
                text = context.getString(R.string.widget_nothing_left),
                size = size,
                muted = true,
            )
        } else {
            // The rows carry their own vertical padding, so there is no spacer
            // between them to spend a child slot on.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                remaining.take(size.timelineRows.coerceAtMost(CHILD_LIMIT)).forEach { entry ->
                    when (entry) {
                        is RibbonEntry.OfLesson -> TimelineRow(
                            lesson = entry.lesson,
                            size = size,
                            options = options,
                            isCurrent = entry.lesson == current,
                            // 110 dp holds a time and a subject and nothing else;
                            // a room number there costs the subject its last
                            // five characters.
                            compact = size.isNarrow,
                        )

                        is RibbonEntry.OfEvent -> EventRow(event = entry.event, size = size)

                        // `remaining` never hands one over — the widget lists
                        // what is left, and a break is a gap between two things
                        // rather than one of them. The branch is here because
                        // the ribbon carries breaks for the day screen, which
                        // draws them as rows of their own.
                        is RibbonEntry.OfBreak -> Unit
                    }
                }
            }
        }
        if (size.showsTodayHomework) {
            VSpace(6)
            TodayHomeworkLine(today = today, size = size)
        }
    }
}

/**
 * "ДЗ на сегодня · 3 предмета", or that nothing was set.
 *
 * One line, not a block. During the school day today's homework is a fact to
 * check rather than a list to read — the list is one tap away, and the tap is
 * the whole widget.
 */
@Composable
private fun TodayHomeworkLine(
    today: SchoolDay?,
    size: WidgetSizeClass,
) {
    val context = LocalContext.current
    val subjects = subjectsIn(today?.homework.orEmpty())
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        CaptionText(
            text = context.getString(R.string.widget_homework_today_title),
            size = size,
            modifier = GlanceModifier.defaultWeight(),
        )
        HSpace(6)
        CaptionText(
            text = if (subjects == 0) {
                context.getString(R.string.widget_homework_empty)
            } else {
                WidgetStrings.subjectCount(context, subjects)
            },
            size = size,
            emphasised = subjects > 0,
        )
    }
}

/**
 * Block four: the next school day — its homework, and what it looks like.
 *
 * @param withPlan adds "6 уроков · Алгебра в 09:00". It is what fills a tall
 *   widget at five in the afternoon, when the rest of today is empty and the
 *   only useful question left is about tomorrow.
 */
@Composable
private fun AheadBlock(
    homework: HomeworkPresentation,
    nextDay: SchoolDay?,
    size: WidgetSizeClass,
    withPlan: Boolean,
) {
    val context = LocalContext.current
    val plan = if (withPlan) dayPlanOf(context, nextDay) else null

    WidgetCard(size = size) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            if (plan != null) {
                CaptionText(
                    text = context.getString(R.string.widget_next_day_title),
                    size = size,
                )
                VSpace(2)
                BodyText(text = plan.ellipsize(size.homeworkChars), size = size)
                VSpace(6)
            }
            HomeworkBlock(
                homework = homework,
                size = size,
                // The timeline above has already taken most of the height, so the
                // homework block gets a smaller slice than it would as the
                // primary content.
                maxItems = (size.homeworkItems - 2).coerceAtLeast(1),
                shortHeader = size.isNarrow,
            )
        }
    }
}

/**
 * After the last bell, all evening and all weekend: the widget stops being a
 * clock and becomes a homework reminder.
 *
 * The plan for the next school day is shown here on every size that has room,
 * not only the tallest — after school there is no timeline competing for the
 * space, and "6 уроков, первый в 09:00" is the other half of what somebody
 * packing a bag needs.
 */
@Composable
private fun RestDayBody(
    state: DayState,
    homework: HomeworkPresentation,
    nextDay: SchoolDay?,
    size: WidgetSizeClass,
) {
    val context = LocalContext.current
    val plan = if (size.showsMeta) dayPlanOf(context, nextDay) else null

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            // Not `headlineOf(...).label`, which is the full sentence. This is
            // the one card where a state label meets a 110 dp column —
            // `StackBody` serves the narrow rungs too — and `StateLabel` is one
            // line that Glance cannot ellipsize, so «УРОКИ ЗАКОНЧИЛИСЬ» was
            // clipped mid-word rather than shortened.
            StateLabel(
                text = WidgetStrings.restStateLabel(context, state, narrow = size.isNarrow),
                size = size,
            )
            (state as? DayState.DayOff)?.note?.takeIf { it.isNotBlank() }?.let { note ->
                VSpace(2)
                CaptionText(text = note.ellipsize(size.homeworkChars), size = size)
            }
            if (plan != null) {
                VSpace(2)
                CaptionText(text = plan.ellipsize(size.homeworkChars), size = size)
            }
        }
        VSpace(8)
        HomeworkBlock(
            homework = homework,
            size = size,
            maxItems = size.homeworkItems,
            // By width, not by naming the sizes one at a time — the same fix
            // `EmptyBody` already carries, and for the same reason: the list
            // was written when there were three narrow rungs and did not grow
            // when two more arrived. `NARROW` is the same 110 dp as the two
            // named here, so it was handed «Домашнее задание на понедельник»
            // into 90 dp of width, where it wraps to two lines and pushes the
            // third homework row off the bottom. The same widget an hour
            // earlier takes the `AheadBlock` path and reads «ДЗ на …».
            shortHeader = size.isNarrow,
            itemMaxLines = if (size.timelineRows > 0) 2 else 1,
        )
    }
}

/**
 * How long is left, ticking.
 *
 * The widget is otherwise a snapshot: the process wakes on an alarm, renders,
 * and sleeps, so "осталось 12 мин" stayed at twelve until the next wake — which
 * is exactly wrong for the one number on the screen that a pupil watches. The
 * remaining time is drawn by a `Chronometer` counting down in the launcher's own
 * process, second by second, costing nothing.
 *
 * The frozen string below is a guard, not a path anybody takes. `ScheduleEngine`
 * fills `validUntil` on all four states that carry a countdown, so
 * [Headline.endsAt] is null exactly where [Headline.countdown] is, and the
 * branch cannot fire as the widget stands. It is kept because `validUntil` is
 * nullable on every `DayState` and only that one producer makes the invariant
 * true: a state that counted something with no end would otherwise draw
 * *nothing* where the number goes, silently. An earlier version of this comment
 * claimed the branch was what after-school renders take. It is not — after
 * school there is no countdown at all, and both strings are null together.
 */
@Composable
private fun Countdown(
    headline: Headline,
    now: LocalDateTime,
    size: WidgetSizeClass,
    labelled: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    val endsAt = headline.endsAt
    if (endsAt == null) {
        val frozen = if (labelled) headline.countdown else headline.bareCountdown
        frozen?.let { CaptionText(text = it, size = size, emphasised = true, modifier = modifier) }
        return
    }

    LiveCountdown(
        endsAt = endsAt,
        now = now,
        size = size,
        urgent = headline.accentIsUrgent,
        label = headline.countdownWord.takeIf { labelled },
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

// `upcomingLessons` lives in WidgetPresentation.kt with the rest of the pure
// selectors: it is the half of the "Дальше" column that can be wrong, and a
// private function in a file full of composables is a function no test can
// reach. It was wrong for exactly as long as it was unreachable.

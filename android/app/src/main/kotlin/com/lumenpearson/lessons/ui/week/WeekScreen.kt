package com.lumenpearson.lessons.ui.week

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.LessonGroup
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SegmentedPicker
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.Term
import com.lumenpearson.lessons.core.model.TermKind
import com.lumenpearson.lessons.ui.common.asDayMonth
import com.lumenpearson.lessons.ui.common.asFullWeekday
import com.lumenpearson.lessons.ui.common.asMonthYear
import com.lumenpearson.lessons.ui.common.asShortWeekday
import java.time.LocalDate
import java.util.Locale

/**
 * The calendar tab: the same timetable at three scales.
 *
 * The week is what a pupil looks at most; the month answers "when is that trip"
 * without stepping through four weeks; the day against an hour ruler is the only
 * one of the three in which a forty-minute gap between lessons looks like a gap
 * rather than like two rows next to each other.
 *
 * There is no pager inside this screen. There used to be — seven day pages under
 * the shell's own pager — and the inner one took every horizontal swipe, so on
 * this tab the gesture paged to Wednesday instead of moving to Homework, and
 * turning "swipe between tabs" off in settings did not disable it either. The
 * day is chosen by tapping, and the period is stepped with the arrows.
 */
@Composable
fun WeekScreen(
    modifier: Modifier = Modifier,
    viewModel: WeekViewModel = viewModel(factory = WeekViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // One scroll for the whole page: the title, the picker and the grid all move
    // under the status bar, which is what the fade up there is for.
    val scrollState = rememberScrollState()
    ReportScrollOffset(scrollState)

    var openLesson by remember { mutableStateOf<Lesson?>(null) }
    var openDay by remember { mutableStateOf<LocalDate?>(null) }

    val selected = state.selectedDay

    openLesson?.let { lesson ->
        LessonSheet(
            lesson = lesson,
            date = state.selected,
            homework = selected?.day?.homework.orEmpty()
                .filter { it.subject.equals(lesson.subject, ignoreCase = true) },
            showTeacher = state.showTeacher,
            onDismiss = { openLesson = null },
        )
    }

    openDay?.let { date ->
        val day = state.days.firstOrNull { it.date == date }
        DaySheet(
            day = day,
            date = date,
            showTeacher = state.showTeacher,
            showEvents = state.showEvents,
            showHomework = state.showHomework,
            onLessonClick = { lesson ->
                openDay = null
                openLesson = lesson
            },
            onDismiss = { openDay = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .appScrollMotionBlur(scrollState)
            .verticalScroll(scrollState)
            .padding(
                top = statusBarSpace() + 8.dp,
                bottom = LocalBottomBarSpace.current,
            ),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        ScheduleHeader(
            periodLabel = state.periodLabel(),
            termLabel = state.termLabel(),
            showTodayAction = state.canReturnToToday,
            onToday = viewModel::showToday,
            onPrevious = viewModel::showPrevious,
            onNext = viewModel::showNext,
        )

        SegmentedPicker(
            items = ScheduleView.entries,
            selectedItem = state.view,
            onItemSelected = viewModel::setView,
            labelProvider = { view -> correctedString(view.labelRes) },
            containerColor = MaterialTheme.colorScheme.rowContainer,
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier
                .padding(horizontal = ScreenPadding)
                .clip(LessonsShapeTokens.Group),
        )

        // Keyed on the period as well as the view, so stepping a week slides the
        // new one in from the side the arrow pointed at.
        AnimatedContent(
            targetState = state.view to state.periodStart,
            transitionSpec = {
                val forward = targetState.second >= initialState.second
                val enter = slideInHorizontally(tween(PeriodTransitionMillis)) { width ->
                    if (forward) width / 6 else -width / 6
                } + fadeIn(tween(PeriodTransitionMillis))
                val exit = slideOutHorizontally(tween(PeriodTransitionMillis)) { width ->
                    if (forward) -width / 6 else width / 6
                } + fadeOut(tween(PeriodTransitionMillis))
                enter togetherWith exit
            },
            label = "schedule_period",
        ) { (view, _) ->
            Column(verticalArrangement = Arrangement.spacedBy(GroupSpacing)) {
                when (view) {
                    ScheduleView.WEEK -> WeekdaySelector(
                        days = state.days,
                        selected = state.selected,
                        showLoad = state.showLoad,
                        onSelect = viewModel::select,
                    )

                    ScheduleView.MONTH -> MonthGrid(
                        days = state.days,
                        selected = state.selected,
                        showLoad = state.showLoad,
                        onSelect = viewModel::select,
                        onOpen = { date -> openDay = date },
                    )

                    ScheduleView.DAY -> Unit
                }
            }
        }

        when (state.view) {
            ScheduleView.DAY -> HourTimeline(
                day = selected,
                date = state.selected,
                nowAt = state.nowAt,
                showEvents = state.showEvents,
                showHomework = state.showHomework,
                onLessonClick = { lesson -> openLesson = lesson },
            )

            else -> DayPanel(
                day = selected,
                date = state.selected,
                showTeacher = state.showTeacher,
                showEvents = state.showEvents,
                showHomework = state.showHomework,
                onLessonClick = { lesson -> openLesson = lesson },
                onOpenDay = { openDay = state.selected },
            )
        }
    }
}

/** How long a period change takes to slide across. */
private const val PeriodTransitionMillis = 260

/** Label of a view in the segmented picker. */
private val ScheduleView.labelRes: Int
    get() = when (this) {
        ScheduleView.WEEK -> R.string.schedule_view_week
        ScheduleView.MONTH -> R.string.schedule_view_month
        ScheduleView.DAY -> R.string.schedule_view_day
    }

/**
 * What the header says the screen is showing.
 *
 * The week names its first and last *drawn* date rather than the period's ends,
 * so a strip with weekends hidden is headed "8 – 12 сентября" instead of
 * promising a Sunday that is not on it.
 */
@Composable
private fun ScheduleUiState.periodLabel(): String = when (view) {
    ScheduleView.WEEK -> correctedString(
        R.string.week_range,
        (days.firstOrNull()?.date ?: periodStart).asDayMonth(),
        (days.lastOrNull()?.date ?: periodEnd).asDayMonth(),
    )

    ScheduleView.MONTH -> anchor.asMonthYear()
    ScheduleView.DAY -> "${selected.asFullWeekday().replaceFirstChar { it.uppercase() }}, " +
        selected.asDayMonth()
}

/**
 * The term the selected day belongs to, appended to the header.
 *
 * Empty during the holidays and for a class whose server has no terms — in both
 * cases the header is just the period, because «—» in place of a term name
 * claims the school has one and the app forgot it.
 */
@Composable
private fun ScheduleUiState.termLabel(): String? = selectedTerm?.label()

/**
 * «2 четверть» / «1 полугодие», and their English twins.
 *
 * Here rather than on [Term], which lives in `:core:model` — a pure JVM module
 * that has no resources and cannot have any. The sentence was written into that
 * type, so this header drew a Russian term name next to an English month for
 * every phone reading the app in English, and no folder-comparison could see it
 * because the words were in neither folder.
 *
 * `internal` so the test can compose it: the header this feeds is private, and
 * the one thing worth pinning is that both languages come out of resources.
 */
@Composable
internal fun Term.label(): String = when (kind) {
    TermKind.QUARTER -> correctedString(R.string.term_quarter, index)
    TermKind.SEMESTER -> correctedString(R.string.term_semester, index)
}

/**
 * The page title and the three period controls.
 *
 * The controls used to be actions in a top app bar. They are here because there
 * is no top app bar any more, and because the top-right corner of a 6.7 inch
 * phone was a poor place for the one pair of buttons on this screen that
 * anybody presses repeatedly.
 */
@Composable
private fun ScheduleHeader(
    periodLabel: String,
    termLabel: String?,
    showTodayAction: Boolean,
    onToday: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenHeader(
            title = correctedString(R.string.week_title),
            // «Октябрь 2026 · 1 четверть». One line rather than two: the term
            // qualifies the period rather than standing beside it, and a second
            // line pushes the grid down on every phone for a word.
            subtitle = listOfNotNull(periodLabel, termLabel).joinToString(" · "),
            modifier = Modifier.weight(1f),
        )
        // Only offered when it would do something: a "back to today" button on
        // a period that already contains today is noise.
        if (showTodayAction) {
            IconButton(onClick = onToday) {
                Icon(
                    imageVector = Icons.Rounded.Today,
                    contentDescription = correctedString(R.string.week_current),
                )
            }
        }
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Rounded.ChevronLeft,
                contentDescription = correctedString(R.string.week_previous),
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = correctedString(R.string.week_next),
            )
        }
    }
}

/**
 * The week strip.
 *
 * Seven tiles need about 400 dp and a phone has 360, so the last day or two
 * start off-screen; without the scroll-to below, the row never moved and the
 * selected chip could not be seen at all. Five tiles — weekends hidden — fit,
 * and the scroll-to costs nothing when there is nowhere to scroll.
 */
@Composable
private fun WeekdaySelector(
    days: List<WeekDayUi>,
    selected: LocalDate,
    showLoad: Boolean,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedIndex = days.indexOfFirst { it.date == selected }
    LaunchedEffect(selectedIndex, days.size) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items = days, key = { _, day -> day.date.toString() }) { _, day ->
            WeekdayTile(
                weekday = day.date.asShortWeekday(),
                dayOfMonth = day.date.dayOfMonth.toString(),
                lessonCount = if (showLoad) day.day?.activeLessons?.size ?: 0 else 0,
                selected = day.date == selected,
                isToday = day.isToday,
                onClick = { onSelect(day.date) },
            )
        }
    }
}

/**
 * One day of the strip: the weekday over the date, in a rounded tile.
 *
 * Two lines rather than one chip because "чт" alone is ambiguous the moment the
 * user steps away from the current week.
 */
@Composable
private fun WeekdayTile(
    weekday: String,
    dayOfMonth: String,
    lessonCount: Int,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primary else scheme.rowContainer
    val content = if (selected) scheme.onPrimary else scheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clip(LessonsShapeTokens.Row)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = weekday,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
        Text(
            text = dayOfMonth,
            // Bold for the day in view and for today, which has no fill of its
            // own in the strip and would otherwise carry no mark at all.
            style = MaterialTheme.typography.titleMedium.emphasised(selected || isToday),
            color = if (selected) scheme.onPrimary else scheme.onSurface,
        )
        LoadDots(
            count = lessonCount,
            color = if (selected) scheme.onPrimary else scheme.primary,
        )
    }
}

/**
 * How busy a day is, as up to three dots.
 *
 * A number would be read; dots are seen. Three is the cap because the difference
 * that matters in a grid is none / a few / a full day, and a fourth dot only
 * makes the cell taller.
 */
@Composable
private fun LoadDots(
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val dots = count.coerceAtMost(MaxLoadDots)
    Row(
        modifier = modifier.height(DotSize),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(dots) {
            Box(
                modifier = Modifier
                    .size(DotSize)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(color),
            )
        }
    }
}

private const val MaxLoadDots = 3
private val DotSize: Dp = 4.dp

/**
 * A month as a seven-column grid.
 *
 * The corners are filled with the neighbouring months' days rather than left
 * blank: a grid that starts mid-row is harder to read than one that does not,
 * and a blank cell is a cell nobody can tap. They are drawn faint, and tapping
 * one steps the month rather than selecting a date the grid does not own.
 *
 * A tap selects; a second tap on the same day opens its sheet. One gesture for
 * "show me this day below" and one for "show me everything", which is what the
 * month view is for — the grid itself has room for a number and three dots.
 */
@Composable
private fun MonthGrid(
    days: List<WeekDayUi>,
    selected: LocalDate,
    showLoad: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpen: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val weeks = remember(days) { days.chunked(DaysPerRow) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.take(DaysPerRow).forEach { day ->
                Text(
                    text = day.date.asShortWeekday(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        weeks.forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    MonthCell(
                        day = day,
                        selected = day.date == selected,
                        showLoad = showLoad,
                        onClick = {
                            if (day.date == selected) onOpen(day.date) else onSelect(day.date)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private const val DaysPerRow = 7

/** One cell of the month grid. */
@Composable
private fun MonthCell(
    day: WeekDayUi,
    selected: Boolean,
    showLoad: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        selected -> scheme.primary
        day.isToday -> scheme.secondaryContainer
        day.inPeriod -> scheme.rowContainer
        else -> Color.Transparent
    }
    val content = when {
        selected -> scheme.onPrimary
        day.isToday -> scheme.onSecondaryContainer
        day.inPeriod -> scheme.onSurface
        else -> scheme.outline
    }

    Column(
        modifier = modifier
            .clip(LessonsShapeTokens.Row)
            .background(container)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium.emphasised(selected || day.isToday),
            color = content,
        )
        LoadDots(
            count = if (showLoad) day.day?.activeLessons?.size ?: 0 else 0,
            color = if (selected) scheme.onPrimary else scheme.primary,
        )
    }
}

/** The detail under the week strip or the month grid. */
@Composable
private fun DayPanel(
    day: WeekDayUi?,
    date: LocalDate,
    showTeacher: Boolean,
    showEvents: Boolean,
    showHomework: Boolean,
    onLessonClick: (Lesson) -> Unit,
    onOpenDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val schoolDay = day?.day
    val lessons = schoolDay?.activeLessons.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        SectionHeader(
            title = "${date.asFullWeekday().replaceFirstChar { it.uppercase() }}, " +
                date.asDayMonth(),
            subtitle = schoolDay?.let {
                pluralStringResource(
                    R.plurals.lessons_count,
                    it.activeLessons.size,
                    it.activeLessons.size,
                )
            },
            actionLabel = schoolDay?.let { correctedString(R.string.schedule_day_details) },
            onActionClick = schoolDay?.let { { onOpenDay() } },
        )

        DayChips(day = day, date = date)

        when {
            schoolDay == null -> EmptyState(
                title = correctedString(R.string.week_no_data_title),
                description = correctedString(R.string.week_no_data_description),
            )

            lessons.isEmpty() -> EmptyState(
                title = correctedString(R.string.week_day_off_title),
                description = correctedString(R.string.week_day_off_description),
            )

            else -> LessonGroup(
                lessons = lessons,
                now = null,
                showTeacher = showTeacher,
                onLessonClick = onLessonClick,
            )
        }

        DayExtras(
            day = schoolDay,
            showEvents = showEvents,
            showHomework = showHomework,
        )
    }
}

/** Today / kind / note, as a row of pills. */
@Composable
private fun DayChips(
    day: WeekDayUi?,
    date: LocalDate,
    modifier: Modifier = Modifier,
) {
    val kind = day?.day?.kind?.takeIf { it != DayKind.NORMAL }
    val note = day?.day?.note
    val isToday = day?.isToday == true
    if (!isToday && kind == null && note == null) return

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isToday) {
            PillChip(
                text = correctedString(R.string.day_today),
                selected = true,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
        if (kind != null) PillChip(text = kind.asLabel())
        if (note != null) PillChip(text = note)
    }
}

/** Localized name of a non-normal day kind. */
@Composable
internal fun DayKind.asLabel(): String = correctedString(
    when (this) {
        DayKind.NORMAL -> R.string.day_kind_normal
        DayKind.HOLIDAY -> R.string.day_kind_holiday
        DayKind.SHORTENED -> R.string.day_kind_shortened
        DayKind.REMOTE -> R.string.day_kind_remote
    },
)

/**
 * The hour ruler.
 *
 * Blocks are positioned by time rather than stacked in order, which is the whole
 * point of this view: a forty-minute window between two lessons is forty minutes
 * of empty space, not a gap you have to work out by reading two clocks. Events
 * get their own column beside the lessons, because a canteen slot that overlaps
 * a lesson is information and a list cannot show an overlap at all.
 */
@Composable
private fun HourTimeline(
    day: WeekDayUi?,
    date: LocalDate,
    nowAt: java.time.LocalTime?,
    showEvents: Boolean,
    showHomework: Boolean,
    onLessonClick: (Lesson) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val schoolDay = day?.day
    val lessons = schoolDay?.activeLessons.orEmpty()
    val events = schoolDay?.events.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        DayChips(day = day, date = date)

        if (schoolDay == null) {
            EmptyState(
                title = correctedString(R.string.week_no_data_title),
                description = correctedString(R.string.week_no_data_description),
            )
            return@Column
        }
        if (lessons.isEmpty() && events.isEmpty()) {
            EmptyState(
                title = correctedString(R.string.week_day_off_title),
                description = correctedString(R.string.week_day_off_description),
            )
            return@Column
        }

        val starts = lessons.map { it.startsAt } + events.map { it.startsAt }
        val ends = lessons.map { it.endsAt } + events.map { it.endsAt }
        val firstHour = starts.minOf { it.hour }
        val lastHour = ends.maxOf { if (it.minute == 0) it.hour else it.hour + 1 }
        val hours = (lastHour - firstHour).coerceAtLeast(1)
        val originMinutes = firstHour * MinutesPerHour

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.width(HourGutterWidth)) {
                repeat(hours) { offset ->
                    Text(
                        // Locale.ROOT: the gutter is a clock, and a clock is
                        // read the same in every language this app is drawn in.
                        // See `BellsSheet.asBellClock`.
                        text = String.format(Locale.ROOT, "%02d:00", firstHour + offset),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.outline,
                        modifier = Modifier.height(HourHeight),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(HourHeight * hours),
            ) {
                lessons.forEach { lesson ->
                    val tone = subjectTone(lesson.subject, lesson.colorHex)
                    TimelineBlock(
                        title = lesson.subject,
                        subtitle = lesson.room?.let {
                            correctedString(R.string.schedule_room_short, it)
                        },
                        startMinutes = lesson.startsAt.minutesOfDay() - originMinutes,
                        endMinutes = lesson.endsAt.minutesOfDay() - originMinutes,
                        container = tone.container,
                        content = tone.content,
                        onClick = { onLessonClick(lesson) },
                    )
                }

                if (nowAt != null) {
                    val offset = nowAt.minutesOfDay() - originMinutes
                    if (offset in 0..(hours * MinutesPerHour)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = HourHeight * (offset / MinutesPerHour.toFloat()))
                                .height(2.dp)
                                .background(scheme.error),
                        )
                    }
                }
            }

            if (events.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .width(EventColumnWidth)
                        .height(HourHeight * hours),
                ) {
                    events.forEach { event ->
                        TimelineBlock(
                            title = event.title,
                            subtitle = event.location,
                            startMinutes = event.startsAt.minutesOfDay() - originMinutes,
                            endMinutes = event.endsAt.minutesOfDay() - originMinutes,
                            container = scheme.secondaryContainer,
                            content = scheme.onSecondaryContainer,
                            onClick = null,
                        )
                    }
                }
            }
        }

        DayExtras(
            day = schoolDay,
            showEvents = showEvents,
            showHomework = showHomework,
        )
    }
}

/** Height of one hour of the ruler, and the widths beside it. */
private val HourHeight: Dp = 68.dp
private val HourGutterWidth: Dp = 44.dp
private val EventColumnWidth: Dp = 96.dp
private const val MinutesPerHour = 60

/** Minutes since midnight; the ruler's only coordinate. */
private fun java.time.LocalTime.minutesOfDay(): Int = hour * MinutesPerHour + minute

/** One block on the ruler, positioned and sized by its own start and end. */
@Composable
private fun TimelineBlock(
    title: String,
    subtitle: String?,
    startMinutes: Int,
    endMinutes: Int,
    container: Color,
    content: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val top = HourHeight * (startMinutes / MinutesPerHour.toFloat())
    // A ten-minute lesson would otherwise be a colour with no room for a word in
    // it; below the floor the block stops shrinking and starts overlapping, which
    // is at least legible.
    val height = (HourHeight * ((endMinutes - startMinutes) / MinutesPerHour.toFloat()))
        .coerceAtLeast(MinBlockHeight)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = top)
            .height(height)
            .padding(end = 4.dp, bottom = 2.dp)
            .clip(LessonsShapeTokens.Row)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            MarqueeText(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = content,
            )
        }
    }
}

private val MinBlockHeight: Dp = 30.dp

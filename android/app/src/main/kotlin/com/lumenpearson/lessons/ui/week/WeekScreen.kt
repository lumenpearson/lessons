package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.LessonGroup
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.ui.common.asDayMonth
import com.lumenpearson.lessons.ui.common.asFullWeekday
import com.lumenpearson.lessons.ui.common.asShortWeekday

/**
 * The week, one day at a time.
 *
 * One day rather than a scrolling list of seven: a school week is read one day
 * at a time ("what do I need for Thursday?"), and this keeps every day starting
 * from the same place on screen instead of at a random scroll offset.
 *
 * The day is chosen from the chip row and nothing else. It used to be a
 * `HorizontalPager`, which put a second horizontal pager inside the shell's
 * one: the inner pager won every drag, so on this tab — and only this tab — a
 * sideways swipe paged through Tuesday and Wednesday instead of moving to the
 * next destination. A quarter of the app had no tab swipe at all, and turning
 * "свайп между вкладками" off in settings did not disable this one either.
 */
@Composable
fun WeekScreen(
    modifier: Modifier = Modifier,
    viewModel: WeekViewModel = viewModel(factory = WeekViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selectedDay by rememberSaveable { mutableIntStateOf(state.initialPage) }

    // Changing week re-anchors the selection: today for the current week, Monday
    // otherwise. Keyed on the week itself so picking a day within one is left
    // alone.
    LaunchedEffect(state.weekStart, state.days.size) {
        if (state.days.isNotEmpty()) {
            selectedDay = state.initialPage.coerceIn(0, state.days.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LessonsTopAppBar(
                title = stringResource(R.string.week_title),
                subtitle = stringResource(
                    R.string.week_range,
                    state.weekStart.asDayMonth(),
                    state.weekEnd.asDayMonth(),
                ),
                scrollBehavior = scrollBehavior,
                actions = {
                    // Only offered when it would do something: a "back to today"
                    // button on the current week is noise.
                    if (state.weekOffset != 0) {
                        IconButton(onClick = viewModel::showCurrentWeek) {
                            Icon(
                                imageVector = Icons.Rounded.Today,
                                contentDescription = stringResource(R.string.week_current),
                            )
                        }
                    }
                    IconButton(onClick = viewModel::showPreviousWeek) {
                        Icon(
                            imageVector = Icons.Rounded.ChevronLeft,
                            contentDescription = stringResource(R.string.week_previous),
                        )
                    }
                    IconButton(onClick = viewModel::showNextWeek) {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = stringResource(R.string.week_next),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            WeekdaySelector(
                days = state.days,
                selectedPage = selectedDay,
                onSelect = { page -> selectedDay = page },
            )

            state.days.getOrNull(selectedDay)?.let { day ->
                WeekDayPage(day = day, showTeacher = state.showTeacher)
            }
        }
    }
}

/**
 * The Mon–Sun selector.
 *
 * Selection is derived from the pager rather than stored: two sources of truth
 * for "which day" is exactly how a selector and its content start disagreeing.
 */
@Composable
private fun WeekdaySelector(
    days: List<WeekDayUi>,
    selectedPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Seven tiles need about 400 dp and a phone has 360, so the last day or two
    // start off-screen. Without this the row never moved and the selected chip
    // could not be seen at all.
    val listState = rememberLazyListState()
    LaunchedEffect(selectedPage) {
        if (selectedPage >= 0) listState.animateScrollToItem(selectedPage)
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items = days, key = { _, day -> day.date.toString() }) { index, day ->
            WeekdayTile(
                weekday = day.date.asShortWeekday(),
                dayOfMonth = day.date.dayOfMonth.toString(),
                selected = index == selectedPage,
                isToday = day.isToday,
                onClick = { onSelect(index) },
            )
        }
    }
}

/**
 * One day of the selector: the weekday over the date, in a rounded tile.
 *
 * Two lines rather than one chip because "чт" alone is ambiguous the moment the
 * user pages away from the current week.
 */
@Composable
private fun WeekdayTile(
    weekday: String,
    dayOfMonth: String,
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
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
        )
        // The dot is the only mark today gets when it is not the selected page;
        // colouring the whole tile would compete with the selection itself. Drawn
        // transparent rather than skipped so every tile keeps the same height.
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(LessonsShapeTokens.Pill)
                .background(
                    when {
                        !isToday -> Color.Transparent
                        selected -> scheme.onPrimary
                        else -> scheme.primary
                    },
                ),
        )
    }
}

/** One day: header, whatever deviates from normal, then the lessons. */
@Composable
private fun WeekDayPage(
    day: WeekDayUi,
    showTeacher: Boolean,
    modifier: Modifier = Modifier,
) {
    val schoolDay = day.day
    val lessons = schoolDay?.lessons?.sortedBy { it.startsAt }.orEmpty()

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .appScrollMotionBlur(scrollState)
            .verticalScroll(scrollState)
            .padding(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 4.dp,
                bottom = LocalBottomBarSpace.current,
            ),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        SectionHeader(
            title = "${day.date.asFullWeekday().replaceFirstChar { it.uppercase() }}, " +
                day.date.asDayMonth(),
            subtitle = schoolDay?.let {
                pluralStringResource(
                    R.plurals.lessons_count,
                    it.activeLessons.size,
                    it.activeLessons.size,
                )
            },
        )

        val kind = schoolDay?.kind?.takeIf { it != DayKind.NORMAL }
        val note = schoolDay?.note
        if (day.isToday || kind != null || note != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (day.isToday) {
                    PillChip(
                        text = stringResource(R.string.day_today),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                if (kind != null) PillChip(text = kind.asLabel())
                if (note != null) PillChip(text = note)
            }
        }

        when {
            schoolDay == null -> EmptyState(
                title = stringResource(R.string.week_no_data_title),
                description = stringResource(R.string.week_no_data_description),
            )

            lessons.isEmpty() -> EmptyState(
                title = stringResource(R.string.week_day_off_title),
                description = stringResource(R.string.week_day_off_description),
            )

            else -> LessonGroup(
                lessons = lessons,
                now = null,
                showTeacher = showTeacher,
            )
        }
    }
}

/** Localized name of a non-normal day kind. */
@Composable
private fun DayKind.asLabel(): String = stringResource(
    when (this) {
        DayKind.NORMAL -> R.string.day_kind_normal
        DayKind.HOLIDAY -> R.string.day_kind_holiday
        DayKind.SHORTENED -> R.string.day_kind_shortened
        DayKind.REMOTE -> R.string.day_kind_remote
    },
)

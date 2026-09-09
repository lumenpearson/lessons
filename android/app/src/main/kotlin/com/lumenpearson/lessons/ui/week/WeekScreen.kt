package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.LessonRow
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.ui.common.asDayMonth
import com.lumenpearson.lessons.ui.common.asFullWeekday
import com.lumenpearson.lessons.ui.common.asShortWeekday
import kotlinx.coroutines.launch

/**
 * The week, one day per page.
 *
 * A pager rather than a scrolling list of seven days: a school week is read one
 * day at a time ("what do I need for Thursday?"), and swiping keeps every day
 * starting from the same place on screen instead of at a random scroll offset.
 * The chip row is the same selection expressed as a map, so the current day is
 * always visible even mid-swipe.
 */
@Composable
fun WeekScreen(
    modifier: Modifier = Modifier,
    viewModel: WeekViewModel = viewModel(factory = WeekViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = state.initialPage) { state.days.size }

    // Changing week re-anchors the pager: today for the current week, Monday
    // otherwise. Keyed on the week itself so a swipe within a week is untouched.
    LaunchedEffect(state.weekStart, state.days.size) {
        if (state.days.isNotEmpty()) {
            pagerState.scrollToPage(state.initialPage.coerceIn(0, state.days.lastIndex))
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
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
                selectedPage = pagerState.currentPage,
                onSelect = { page ->
                    coroutineScope.launch { pagerState.animateScrollToPage(page) }
                },
            )
            HorizontalDivider()

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 8.dp,
            ) { page ->
                state.days.getOrNull(page)?.let { day ->
                    WeekDayPage(day = day, showTeacher = state.showTeacher)
                }
            }
        }
    }
}

/**
 * The Mon–Sun chip row.
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
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items = days, key = { _, day -> day.date.toString() }) { index, day ->
            PillChip(
                day.date.asShortWeekday(),
                selected = index == selectedPage,
                onClick = { onSelect(index) },
            )
        }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(
                "${day.date.asFullWeekday().replaceFirstChar { it.uppercase() }}, " +
                    day.date.asDayMonth(),
                modifier = Modifier.weight(1f),
            )
            if (day.isToday) {
                PillChip(stringResource(R.string.day_today))
            }
        }

        schoolDay?.kind?.takeIf { it != DayKind.NORMAL }?.let { kind ->
            PillChip(kind.asLabel())
        }
        schoolDay?.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            schoolDay == null -> EmptyState(
                title = stringResource(R.string.week_no_data_title),
                description = stringResource(R.string.week_no_data_description),
                modifier = Modifier.fillMaxWidth(),
            )

            lessons.isEmpty() -> EmptyState(
                title = stringResource(R.string.week_day_off_title),
                description = stringResource(R.string.week_day_off_description),
                modifier = Modifier.fillMaxWidth(),
            )

            else -> {
                Text(
                    text = pluralStringResource(
                        R.plurals.lessons_count,
                        schoolDay.activeLessons.size,
                        schoolDay.activeLessons.size,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lessons.forEach { lesson ->
                    WeekLessonRow(lesson = lesson, showTeacher = showTeacher)
                }
            }
        }
    }
}

/**
 * A lesson plus its deviations.
 *
 * Cancelled and replaced lessons stay in the list — a pupil needs to see that
 * the second lesson is *gone*, not just that it is missing — so the status is
 * spelled out in a chip under the row.
 * // fallback: drop the chips if LessonRow already renders the two flags.
 */
@Composable
private fun WeekLessonRow(
    lesson: Lesson,
    showTeacher: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LessonRow(
            lesson,
            showTeacher = showTeacher,
            modifier = Modifier.fillMaxWidth(),
        )
        if (lesson.isCancelled || lesson.isReplaced || lesson.note != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lesson.isCancelled) {
                    PillChip(stringResource(R.string.lesson_cancelled))
                }
                if (lesson.isReplaced) {
                    PillChip(stringResource(R.string.lesson_replaced))
                }
                lesson.note?.let { note -> PillChip(note) }
            }
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

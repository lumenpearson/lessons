package com.lumenpearson.lessons.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.HomeworkCard
import com.lumenpearson.lessons.core.designsystem.component.LessonTimeline
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.StateHeroCard
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.ui.common.asRelativeDayLabel
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.common.syncedAtLabel
import com.lumenpearson.lessons.ui.common.timeRange

/** How many homework items the home screen previews before deferring to the tab. */
private const val HomeworkPreviewCount = 3

/**
 * The home screen: one glance answers "what now?".
 *
 * Section order is not fixed. While school is on, the timeline leads; once the
 * last bell has rung — or on a day off — homework is promoted to the top, which
 * is the same rule the widget follows, so the two never disagree about what
 * matters at 16:00.
 *
 * @param onOpenHomework opens the homework tab from the preview's footer.
 */
@Composable
fun TodayScreen(
    onOpenHomework: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    state.message?.let { message ->
        val text = message.asText()
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LessonsTopAppBar(
                title = stringResource(R.string.today_title),
                subtitle = state.className,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "hero") {
                    state.state?.let { dayState -> StateHeroCard(dayState) }
                }

                if (state.homeworkFirst) {
                    homeworkSection(state, onOpenHomework)
                    lessonsSection(state)
                } else {
                    lessonsSection(state)
                    homeworkSection(state, onOpenHomework)
                }

                eventsSection(state)

                item(key = "synced") {
                    Text(
                        text = syncedAtLabel(state.syncedAtEpochMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Remaining lessons, or an explanation of why there are none.
 *
 * Only the rest of the day is listed: a pupil looking at their phone during the
 * fourth lesson does not need the first three re-read to them.
 */
private fun LazyListScope.lessonsSection(state: TodayUiState) {
    item(key = "lessons-header") {
        SectionHeader(stringResource(R.string.today_lessons_remaining))
    }
    item(key = "lessons-body") {
        when {
            state.isLoading -> Unit

            state.remainingLessons.isNotEmpty() -> LessonTimeline(
                state.remainingLessons,
                showTeacher = state.showTeacher,
                modifier = Modifier.fillMaxWidth(),
            )

            state.state is DayState.NoData -> EmptyState(
                title = stringResource(R.string.today_no_data_title),
                description = stringResource(R.string.today_no_data_description),
                modifier = Modifier.fillMaxWidth(),
            )

            state.today?.hasLessons == true -> EmptyState(
                title = stringResource(R.string.today_lessons_over_title),
                description = stringResource(R.string.today_lessons_over_description),
                modifier = Modifier.fillMaxWidth(),
            )

            else -> EmptyState(
                title = stringResource(R.string.today_no_lessons_title),
                description = stringResource(R.string.today_no_lessons_description),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Линейка, столовая, экскурсия — today's non-lesson entries, in time order. */
private fun LazyListScope.eventsSection(state: TodayUiState) {
    if (state.events.isEmpty()) return

    item(key = "events-header") {
        SectionHeader(stringResource(R.string.today_events))
    }
    state.events.forEachIndexed { index, event ->
        item(key = "event-$index") { EventRow(event = event) }
    }
}

/**
 * Homework preview.
 *
 * A preview rather than the full list: the homework tab exists for reading, this
 * is there to say "there is something to do, and roughly what".
 */
private fun LazyListScope.homeworkSection(state: TodayUiState, onOpenHomework: () -> Unit) {
    val day: SchoolDay = state.homeworkDay ?: return
    if (day.homework.isEmpty()) return

    item(key = "homework-header") {
        SectionHeader(
            stringResource(
                R.string.today_homework_for,
                day.date.asRelativeDayLabel(state.now.toLocalDate()),
            ),
        )
    }
    day.homework.take(HomeworkPreviewCount).forEachIndexed { index, homework ->
        item(key = "homework-$index") { HomeworkCard(homework) }
    }
    if (day.homework.size > HomeworkPreviewCount) {
        item(key = "homework-more") {
            TextButton(onClick = onOpenHomework, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.today_homework_more,
                        day.homework.size - HomeworkPreviewCount,
                    ),
                )
            }
        }
    }
}

/**
 * A single non-lesson entry.
 *
 * Written here rather than in :core:designsystem because events are the one part
 * of the timeline the widget never renders, so there is nothing to share yet.
 */
@Composable
private fun EventRow(event: SchoolEvent, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (event.kind == EventKind.EVENT) {
                    Icons.Rounded.EventNote
                } else {
                    Icons.Rounded.CalendarMonth
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(
                        timeRange(event.startsAt, event.endsAt),
                        event.location,
                    ).joinToString(separator = " · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            PillChip(event.kind.asLabel())
        }
    }
}

/** Localized name of an event kind, used as the row's trailing chip. */
@Composable
private fun EventKind.asLabel(): String = stringResource(
    when (this) {
        EventKind.EVENT -> R.string.event_kind_event
        EventKind.CANTEEN -> R.string.event_kind_canteen
        EventKind.EXAM -> R.string.event_kind_exam
        EventKind.TRIP -> R.string.event_kind_trip
        EventKind.MEETING -> R.string.event_kind_meeting
    },
)

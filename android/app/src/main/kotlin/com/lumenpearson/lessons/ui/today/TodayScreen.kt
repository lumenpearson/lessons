package com.lumenpearson.lessons.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.HomeworkRow
import com.lumenpearson.lessons.core.designsystem.component.LessonGroup
import com.lumenpearson.lessons.core.designsystem.component.LessonsPullToRefreshBox
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.component.StateHeroCard
import com.lumenpearson.lessons.core.designsystem.state.icon
import com.lumenpearson.lessons.core.designsystem.state.tone
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.ui.common.asRelativeDayLabelAccusative
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.common.syncedAtLabel
import com.lumenpearson.lessons.ui.common.timeRange

/** Accent slot of the "ещё N заданий" row; violet, unused by any event kind. */
private const val HomeworkMoreSlot = 5

/**
 * The home screen: one glance answers "what now?".
 *
 * Section order is not fixed. By default, while school is on, the day leads;
 * once the last bell has rung — or on a day off — homework is promoted to the
 * top, which is the same rule the widget follows, so the two never disagree
 * about what matters at 16:00. Everything the settings can move about this page
 * arrives as a field of [TodayUiState] rather than as a second source of truth
 * read here, so the order and the blocks are decided in one place.
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
    val listState = rememberLazyListState()
    ReportScrollOffset(listState)

    state.message?.let { message ->
        val text = message.asText()
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    // No Scaffold and no top app bar. The page starts at the top of the window
    // so its first rows can pass under the status bar and be softened there —
    // see ScreenHeader — which means the status-bar inset belongs in the list's
    // content padding rather than in padding around the list.
    Box(modifier = modifier.fillMaxSize()) {
        LessonsPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .appScrollMotionBlur(listState),
                contentPadding = PaddingValues(
                    start = ScreenPadding,
                    end = ScreenPadding,
                    top = statusBarSpace() + 8.dp,
                    bottom = LocalBottomBarSpace.current,
                ),
                verticalArrangement = Arrangement.spacedBy(GroupSpacing),
            ) {
                item(key = "header") {
                    ScreenHeader(
                        title = correctedString(R.string.today_title),
                        subtitle = state.className,
                    )
                }

                if (state.showHero) {
                    item(key = "hero") {
                        state.state?.let { dayState -> StateHeroCard(state = dayState) }
                    }
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
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Lifted clear of the floating toolbar, which is drawn after this and
        // sits exactly where an unlifted snackbar would appear.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalBottomBarSpace.current),
        )
    }
}

/**
 * Remaining lessons, or an explanation of why there are none.
 *
 * Only the rest of the day is listed unless the user asked otherwise: a pupil
 * looking at their phone during the fourth lesson does not need the first three
 * re-read to them. The title follows the choice, because "Оставшиеся уроки"
 * over the whole day would be a lie about the list under it.
 */
private fun LazyListScope.lessonsSection(state: TodayUiState) {
    item(key = "lessons") {
        val title = if (state.wholeDay) {
            correctedString(R.string.today_lessons_all)
        } else {
            correctedString(R.string.today_lessons_remaining)
        }
        SectionHeaderedGroup(title = title) {
            when {
                // A shimmering copy of the group rather than a spinner: the page
                // keeps its shape when the real rows land.
                state.isLoading -> SkeletonGroup()

                state.remainingLessons.isNotEmpty() -> LessonGroup(
                    lessons = state.remainingLessons,
                    now = state.now.toLocalTime(),
                    showTeacher = state.showTeacher,
                )

                state.state is DayState.NoData -> EmptyState(
                    title = correctedString(R.string.today_no_data_title),
                    description = correctedString(R.string.today_no_data_description),
                )

                state.today?.hasLessons == true -> EmptyState(
                    title = correctedString(R.string.today_lessons_over_title),
                    description = correctedString(R.string.today_lessons_over_description),
                )

                else -> EmptyState(
                    title = correctedString(R.string.today_no_lessons_title),
                    description = correctedString(R.string.today_no_lessons_description),
                )
            }
        }
    }
}

/** An assembly, lunch, an excursion — today's non-lesson entries, in time order. */
private fun LazyListScope.eventsSection(state: TodayUiState) {
    if (!state.showEvents || state.events.isEmpty()) return

    item(key = "events") {
        SectionHeaderedGroup(title = correctedString(R.string.today_events)) {
            RoundedCardContainer {
                state.events.forEach { event ->
                    GroupItem(
                        title = event.title,
                        subtitle = listOfNotNull(
                            timeRange(event.startsAt, event.endsAt),
                            event.location,
                        ).joinToString(separator = " · "),
                        icon = event.kind.icon(),
                        tone = event.kind.tone(),
                    )
                }
            }
        }
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

    item(key = "homework") {
        val title = correctedString(
            R.string.today_homework_for,
            day.date.asRelativeDayLabelAccusative(state.now.toLocalDate()),
        )
        SectionHeaderedGroup(title = title) {
            RoundedCardContainer {
                day.homework.take(state.homeworkPreview).forEach { homework ->
                    HomeworkRow(item = homework)
                }
                // The "ещё N" affordance is a row of the group rather than a
                // button under it: it is one more thing to read, in the same list.
                if (day.homework.size > state.homeworkPreview) {
                    GroupItem(
                        title = correctedString(
                            R.string.today_homework_more,
                            day.homework.size - state.homeworkPreview,
                        ),
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        tone = accentTone(HomeworkMoreSlot),
                        onClick = onOpenHomework,
                    )
                }
            }
        }
    }
}

/** A label and whatever it labels, kept together so the two never wrap apart. */
@Composable
private fun SectionHeaderedGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        content()
    }
}

package com.lumenpearson.lessons.ui.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.HomeworkRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SegmentedPicker
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.ui.common.asRelativeDayLabel
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.translate.Correctable
import java.time.LocalDate

/**
 * Every piece of homework the cache knows about, grouped by the day it is due.
 *
 * One rounded group per due date, so a week of assignments reads as a handful of
 * blocks rather than a wall of cards.
 *
 * The filter defaults to future work; "все" exists mainly for the case where a
 * pupil is catching up on something they missed, so it is one tap away rather
 * than a setting.
 */
@Composable
fun HomeworkScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeworkViewModel = viewModel(factory = HomeworkViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted above the empty/skeleton branches: remembered inside one of them
    // it is discarded whenever the filter empties the list, so coming back to
    // "Все" threw the reader back to the top of a list they had scrolled.
    val listState = rememberLazyListState()
    ReportScrollOffset(listState)
    val today = LocalDate.now()

    state.message?.let { message ->
        val text = message.asText()
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    // No Scaffold and no top app bar: the page starts at the top of the window
    // so that its rows can pass under the status bar and be softened there.
    // The title and the filter are items of the list rather than a fixed header
    // above it — a pinned strip there would be the one thing that never scrolls
    // under the bar, which is exactly what the fade is for.
    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
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
                    // Two of this screen's strings are wrapped as a worked
                    // example of correction mode reaching ordinary UI: the
                    // header renders whatever the reader has corrected the
                    // title to, and a long press on it opens the editor.
                    Correctable(R.string.homework_title) { title ->
                        ScreenHeader(title = title)
                    }
                }

                item(key = "filter") {
                    HomeworkFilterRow(
                        onlyUpcoming = state.onlyUpcoming,
                        hiddenCount = state.hiddenCount,
                        onSelect = viewModel::setOnlyUpcoming,
                    )
                }

                if (state.groups.isEmpty() && state.isLoading) {
                    item(key = "skeleton") { SkeletonGroup() }
                } else if (state.groups.isEmpty()) {
                    item(key = "empty") {
                        Correctable(R.string.homework_empty_title) { title ->
                            EmptyState(
                                title = title,
                                description = if (state.onlyUpcoming && state.hiddenCount > 0) {
                                    stringResource(R.string.homework_empty_filtered_description)
                                } else {
                                    stringResource(R.string.homework_empty_description)
                                },
                            )
                        }
                    }
                } else {
                    items(
                        items = state.groups,
                        key = { group -> group.date.toString() },
                    ) { group ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = group.date.asRelativeDayLabel(today))
                            RoundedCardContainer {
                                group.items.forEach { homework ->
                                    HomeworkRow(item = homework)
                                }
                            }
                        }
                    }
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
 * A connected button group instead of a switch: the labels say what each state
 * *shows*, which is less ambiguous than a toggle whose off-state has to be
 * inferred. It is the same control the settings screen uses for every either-or
 * choice, so the two screens do not each invent a filter.
 */
@Composable
private fun HomeworkFilterRow(
    onlyUpcoming: Boolean,
    hiddenCount: Int,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allLabel = if (hiddenCount > 0) {
        stringResource(R.string.homework_filter_all_with_count, hiddenCount)
    } else {
        stringResource(R.string.homework_filter_all)
    }

    SegmentedPicker(
        items = listOf(true, false),
        selectedItem = onlyUpcoming,
        onItemSelected = onSelect,
        labelProvider = { upcoming ->
            if (upcoming) stringResource(R.string.homework_filter_upcoming) else allLabel
        },
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

package com.lumenpearson.lessons.ui.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.HomeworkRow
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SegmentedPicker
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.ui.common.asRelativeDayLabel
import com.lumenpearson.lessons.ui.common.asText
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val today = LocalDate.now()

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
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LessonsTopAppBar(
                title = stringResource(R.string.homework_title),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HomeworkFilterRow(
                onlyUpcoming = state.onlyUpcoming,
                hiddenCount = state.hiddenCount,
                onSelect = viewModel::setOnlyUpcoming,
            )

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.groups.isEmpty() && state.isLoading) {
                    SkeletonGroup(
                        modifier = Modifier.padding(
                            horizontal = ScreenPadding,
                            vertical = 4.dp,
                        ),
                    )
                } else if (state.groups.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.homework_empty_title),
                        description = if (state.onlyUpcoming && state.hiddenCount > 0) {
                            stringResource(R.string.homework_empty_filtered_description)
                        } else {
                            stringResource(R.string.homework_empty_description)
                        },
                        modifier = Modifier.padding(ScreenPadding),
                    )
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .appScrollMotionBlur(listState),
                        contentPadding = PaddingValues(
                            start = ScreenPadding,
                            end = ScreenPadding,
                            top = 4.dp,
                            bottom = LocalBottomBarSpace.current,
                        ),
                        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
                    ) {
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
        }
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

package com.lumenpearson.lessons.ui.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.lumenpearson.lessons.core.designsystem.component.HomeworkCard
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.ui.common.asRelativeDayLabel
import com.lumenpearson.lessons.ui.common.asText
import java.time.LocalDate

/**
 * Every piece of homework the cache knows about, grouped by the day it is due.
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
                if (state.groups.isEmpty() && !state.isLoading) {
                    EmptyState(
                        title = stringResource(R.string.homework_empty_title),
                        description = if (state.onlyUpcoming && state.hiddenCount > 0) {
                            stringResource(R.string.homework_empty_filtered_description)
                        } else {
                            stringResource(R.string.homework_empty_description)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.groups.forEach { group ->
                            item(key = "header-${group.date}") {
                                SectionHeader(group.date.asRelativeDayLabel(today))
                            }
                            group.items.forEachIndexed { index, homework ->
                                item(key = "${group.date}-$index") {
                                    HomeworkCard(homework)
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
 * Two chips instead of a switch: the labels say what each state *shows*, which
 * is less ambiguous than a toggle whose off-state has to be inferred.
 */
@Composable
private fun HomeworkFilterRow(
    onlyUpcoming: Boolean,
    hiddenCount: Int,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillChip(
            stringResource(R.string.homework_filter_upcoming),
            selected = onlyUpcoming,
            onClick = { onSelect(true) },
        )
        PillChip(
            if (hiddenCount > 0) {
                stringResource(R.string.homework_filter_all_with_count, hiddenCount)
            } else {
                stringResource(R.string.homework_filter_all)
            },
            selected = !onlyUpcoming,
            onClick = { onSelect(false) },
        )
    }
}

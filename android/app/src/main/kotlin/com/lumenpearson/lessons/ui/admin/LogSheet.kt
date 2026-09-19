package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AuditEntry
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone

/**
 * «📜 Журнал»: who changed what, newest first.
 *
 * Paged with two buttons rather than an endless scroll, because the log is
 * append-only and unbounded and the server answers `has_more` rather than a
 * total — there is no scrollbar to be honest about. The summaries are plain
 * text written by whichever surface made the change, so the bot's lines and the
 * app's read alike here.
 */
@Composable
fun LogSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = state.log.value

    ManagementSheet(
        title = correctedString(R.string.admin_log_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when {
            page == null && state.log.loading -> SkeletonGroup(
                modifier = Modifier.padding(horizontal = ScreenPadding),
                rows = 5,
            )

            page == null -> ManagementFailureCard(
                failure = state.log.failure,
                modifier = Modifier.padding(horizontal = ScreenPadding),
                onRetry = { viewModel.loadLog() },
            )

            page.entries.isEmpty() -> EmptyState(
                title = correctedString(R.string.admin_log_empty_title),
                description = correctedString(R.string.admin_log_empty_text),
                icon = Icons.AutoMirrored.Rounded.ListAlt,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )

            else -> {
                SheetNote(
                    text = correctedString(
                        R.string.admin_log_page,
                        page.offset + 1,
                        page.offset + page.entries.size,
                    ),
                )
                RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    page.entries.forEach { entry ->
                        GroupItem(
                            title = entry.summary,
                            subtitle = entry.meta(),
                            // A hue per action tag, so a page of the log shows
                            // at a glance that six lines are the same kind of
                            // change rather than six unrelated ones.
                            tone = accentTone(entry.action.hashCode()),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = viewModel::showPreviousLogPage,
                        enabled = page.offset > 0 && !state.log.loading,
                    ) {
                        Text(text = correctedString(R.string.admin_log_newer))
                    }
                    TextButton(
                        onClick = viewModel::showNextLogPage,
                        enabled = page.hasMore && !state.log.loading,
                    ) {
                        Text(text = correctedString(R.string.admin_log_older))
                    }
                }
                // A failed page turn leaves the page that is up and says so;
                // clearing the list would lose the thing the user was reading.
                SheetFailure(failure = state.log.failure)
            }
        }
    }
}

/** "@anna · 12.09 14:05", and «система» for a line nobody signed. */
@Composable
private fun AuditEntry.meta(): String {
    val who = who ?: correctedString(R.string.admin_log_system)
    val at = at?.asClassStamp() ?: action
    return correctedString(R.string.admin_log_meta, who, at)
}

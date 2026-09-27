package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.common.SyncIntervalOptionsMinutes
import com.lumenpearson.lessons.ui.common.syncIntervalLabel

/**
 * @param classSync whether the phone has a class for the background sync to
 *   refresh. Without one — the diary home — the interval and «Обновить сейчас»
 *   drive a worker that syncs a class only, so they would be switches that do
 *   nothing; the server address stays, because every diary read goes through
 *   it.
 */
internal fun LazyListScope.syncRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    classSync: Boolean,
    onEditServer: () -> Unit,
) = item(key = "sync") {
    SettingsGroup(title = correctedString(R.string.settings_sync_group)) {
        if (classSync) {
            SyncIntervalRow(
                selectedMinutes = state.settings.syncIntervalMinutes,
                onSelect = viewModel::setSyncInterval,
            )
        }
        GroupLinkItem(
            title = correctedString(R.string.settings_server_url),
            subtitle = state.settings.baseUrl.takeIf { it.isNotBlank() }
                ?: correctedString(R.string.settings_server_url_unset),
            icon = Icons.Rounded.Dns,
            tone = accentTone(0),
            onClick = onEditServer,
        )
        // Filled and full width, as the last row of the group, because it is
        // the one thing on this page that *does* something the moment it is
        // pressed rather than storing a preference. Essentials closes its own
        // updates group with the same shape.
        if (classSync) {
            GroupActionItem(
                label = correctedString(R.string.settings_refresh_now),
                icon = Icons.Rounded.Refresh,
                busy = state.isRefreshing,
                onClick = viewModel::refreshNow,
            )
        }
    }
}

/**
 * Sync cadence as chips rather than a slider: the values are not continuous —
 * WorkManager will not run periodic work more often than every 15 minutes — and
 * a chip row makes the actual choices legible.
 */
@Composable
private fun SyncIntervalRow(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GroupRow(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) {
        AccentIconTile(icon = Icons.Rounded.Update, tone = accentTone(4))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = correctedString(R.string.settings_sync_interval),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // FlowRow because six chips do not fit one line on a small phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SyncIntervalOptionsMinutes.forEach { minutes ->
                    PillChip(
                        text = syncIntervalLabel(minutes),
                        selected = minutes == selectedMinutes,
                        onClick = { onSelect(minutes) },
                    )
                }
            }
        }
    }
}

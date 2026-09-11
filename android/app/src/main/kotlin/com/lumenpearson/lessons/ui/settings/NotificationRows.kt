package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.model.AlertPreferences

/**
 * The notifications page.
 *
 * Four things the app may interrupt for, each with its own switch and, where it
 * makes sense, its own time. They are separate rows rather than one master
 * switch because they are genuinely different decisions: a pupil who wants to be
 * told about a replacement at nine at night does not necessarily want a nudge
 * before every single lesson.
 *
 * Everything here is off until it is turned on. The first row is therefore the
 * permission — turning a switch on while notifications are blocked at the system
 * level would store a preference that silently does nothing, which is the worst
 * possible answer to "why is it not working".
 */
internal fun LazyListScope.notificationRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenSection: (SettingsSection) -> Unit,
) {
    // One row for every permission this app needs rather than the one it used
    // to name, because notifications arriving late is as broken as notifications
    // not arriving, and only one of those two was ever reported here.
    item(key = "notifications-permission") {
        val missing = rememberMissingPermissionCount()
        MissingPermissionsRow(
            missing = missing,
            onOpen = { onOpenSection(SettingsSection.PERMISSIONS) },
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }

    item(key = "notifications-lessons") {
        SettingsGroup(title = stringResource(R.string.settings_alerts_lessons_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_alert_lesson),
                subtitle = stringResource(R.string.settings_alert_lesson_description),
                icon = Icons.Rounded.NotificationsActive,
                tone = accentTone(1),
                checked = state.settings.alerts.lessonSoon,
                onCheckedChange = { on -> viewModel.setAlerts { it.copy(lessonSoon = on) } },
            )
            if (state.settings.alerts.lessonSoon) {
                LeadMinutesRow(
                    selected = state.settings.alerts.lessonLeadMinutes,
                    onSelect = { minutes -> viewModel.setAlerts { it.copy(lessonLeadMinutes = minutes) } },
                )
            }
        }
    }

    item(key = "notifications-daily") {
        SettingsGroup(title = stringResource(R.string.settings_alerts_daily_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_alert_morning),
                subtitle = stringResource(R.string.settings_alert_morning_description),
                icon = Icons.Rounded.WbSunny,
                tone = accentTone(4),
                checked = state.settings.alerts.morningSummary,
                onCheckedChange = { on -> viewModel.setAlerts { it.copy(morningSummary = on) } },
            )
            if (state.settings.alerts.morningSummary) {
                HourRow(
                    title = stringResource(R.string.settings_alert_morning_at),
                    icon = Icons.Rounded.Schedule,
                    tone = accentTone(4),
                    selectedMinutes = state.settings.alerts.morningAtMinutes,
                    onSelect = { minutes -> viewModel.setAlerts { it.copy(morningAtMinutes = minutes) } },
                )
            }
            GroupSwitchItem(
                title = stringResource(R.string.settings_alert_homework),
                subtitle = stringResource(R.string.settings_alert_homework_description),
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                tone = accentTone(3),
                checked = state.settings.alerts.homeworkReminder,
                onCheckedChange = { on -> viewModel.setAlerts { it.copy(homeworkReminder = on) } },
            )
            if (state.settings.alerts.homeworkReminder) {
                HourRow(
                    title = stringResource(R.string.settings_alert_homework_at),
                    icon = Icons.Rounded.Schedule,
                    tone = accentTone(3),
                    selectedMinutes = state.settings.alerts.homeworkAtMinutes,
                    onSelect = { minutes -> viewModel.setAlerts { it.copy(homeworkAtMinutes = minutes) } },
                )
            }
        }
    }

    item(key = "notifications-changes") {
        SettingsGroup(title = stringResource(R.string.settings_alerts_changes_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_alert_changes),
                subtitle = stringResource(R.string.settings_alert_changes_description),
                icon = Icons.Rounded.EditCalendar,
                tone = accentTone(5),
                checked = state.settings.alerts.scheduleChanges,
                onCheckedChange = { on -> viewModel.setAlerts { it.copy(scheduleChanges = on) } },
            )
        }
    }
}

/** How long before the bell, as chips: the choices are not a continuum. */
@Composable
private fun LeadMinutesRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    ChipRow(
        title = stringResource(R.string.settings_alert_lead),
        icon = Icons.Rounded.Schedule,
        tone = accentTone(1),
    ) {
        AlertPreferences.LeadMinuteOptions.forEach { minutes ->
            PillChip(
                text = stringResource(R.string.settings_alert_lead_value, minutes),
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
            )
        }
    }
}

/**
 * An hour of the day, as chips.
 *
 * A time picker would be the obvious control and the wrong one: the app is
 * asking "roughly when", the answers worth giving are whole hours, and a picker
 * would offer 07:23 as though it meant something.
 */
@Composable
private fun HourRow(
    title: String,
    icon: ImageVector,
    tone: AccentTone,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
) {
    ChipRow(title = title, icon = icon, tone = tone) {
        AlertPreferences.HourOptions.forEach { hour ->
            PillChip(
                text = stringResource(R.string.settings_alert_hour_value, hour),
                selected = hour * MinutesPerHour == selectedMinutes,
                onClick = { onSelect(hour * MinutesPerHour) },
            )
        }
    }
}

private const val MinutesPerHour = 60

/** Tile and title on one line, a wrapping row of chips under it. */
@Composable
private fun ChipRow(
    title: String,
    icon: ImageVector,
    tone: AccentTone,
    content: @Composable () -> Unit,
) {
    GroupRow(verticalAlignment = Alignment.Top) {
        AccentIconTile(icon = icon, tone = tone)
        Column(
            modifier = Modifier.padding(end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

package com.lumenpearson.lessons.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
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
) {
    item(key = "notifications-permission") {
        NotificationPermissionRow()
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

/**
 * Shown only while notifications cannot actually be posted.
 *
 * Two different blocks look the same from the app's side: the Android 13
 * runtime permission was never granted, and the user switched the app's
 * notifications off in system settings. The first can be asked for in place; the
 * second can only be undone where it was done, so the row opens that page.
 */
@Composable
private fun NotificationPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(canPostNotifications(context)) }

    // Re-read on every return to the screen. Both remedies leave the app —
    // the permission dialog is a separate window, system settings a separate
    // task — so a row that only checked once would still be telling the user to
    // grant something they had just granted.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = canPostNotifications(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val request = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result || canPostNotifications(context) }

    if (granted) return

    SettingsGroup(title = stringResource(R.string.settings_alerts_blocked_group)) {
        GroupItem(
            title = stringResource(R.string.settings_alerts_blocked),
            subtitle = stringResource(R.string.settings_alerts_blocked_description),
            icon = Icons.Rounded.NotificationsOff,
            tone = errorTone(),
            onClick = {
                // Ask in place where the platform still allows it; otherwise the
                // only thing left is the page where it was switched off.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    request.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings(context)
                }
            },
        )
    }
}

/** Whether a notification posted right now would actually be shown. */
private fun canPostNotifications(context: Context): Boolean {
    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** Opens this app's page in the system notification settings, or does nothing. */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
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

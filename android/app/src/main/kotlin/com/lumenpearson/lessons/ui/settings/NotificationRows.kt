package com.lumenpearson.lessons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
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
import com.lumenpearson.lessons.core.data.notifications.AlertPreview
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.GroupTimeItem
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.LessonAlertDetail

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
 *
 * Under the four switches are two groups that are not about a kind of alert at
 * all. «Когда молчать» holds the rules that apply to every kind — the quiet
 * window and the holidays — because they are one decision and four copies of it
 * would be four ways to get it wrong. «Проверка» posts one sample, which is the
 * only way to find out what all of this actually looks like on a lock screen
 * without waiting until tomorrow morning.
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
                GroupSegmentedItem(
                    title = stringResource(R.string.settings_alert_detail),
                    subtitle = stringResource(R.string.settings_alert_detail_description),
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    tone = accentTone(1),
                    items = LessonAlertDetail.entries,
                    selectedItem = state.settings.alerts.lessonDetail,
                    onItemSelected = { detail -> viewModel.setAlerts { it.copy(lessonDetail = detail) } },
                    labelProvider = { detail -> stringResource(detail.labelRes) },
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
                GroupTimeItem(
                    title = stringResource(R.string.settings_alert_morning_at),
                    icon = Icons.Rounded.Schedule,
                    tone = accentTone(4),
                    minutesOfDay = state.settings.alerts.morningAtMinutes,
                    onMinutesOfDayChange = { minutes ->
                        viewModel.setAlerts { it.copy(morningAtMinutes = minutes) }
                    },
                )
                WeekdayRow(
                    selected = state.settings.alerts.morningWeekdays,
                    onToggle = { day ->
                        viewModel.setAlerts { alerts ->
                            val days = alerts.morningWeekdays
                            alerts.copy(
                                morningWeekdays = if (day in days) days - day else days + day,
                            )
                        }
                    },
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
                GroupTimeItem(
                    title = stringResource(R.string.settings_alert_homework_at),
                    icon = Icons.Rounded.Schedule,
                    tone = accentTone(3),
                    minutesOfDay = state.settings.alerts.homeworkAtMinutes,
                    onMinutesOfDayChange = { minutes ->
                        viewModel.setAlerts { it.copy(homeworkAtMinutes = minutes) }
                    },
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

    // Rules about *whether* to interrupt rather than about what to say, which is
    // why they are one group below the four switches instead of being repeated
    // inside each of them: a quiet hour is quiet for every kind of alert, and
    // four copies of it would be four ways to get it wrong.
    item(key = "notifications-quiet") {
        SettingsGroup(title = stringResource(R.string.settings_alerts_quiet_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_alert_quiet),
                subtitle = stringResource(R.string.settings_alert_quiet_description),
                icon = Icons.Rounded.Bedtime,
                tone = accentTone(0),
                checked = state.settings.alerts.quietHours,
                onCheckedChange = { on -> viewModel.setAlerts { it.copy(quietHours = on) } },
            )
            if (state.settings.alerts.quietHours) {
                GroupTimeItem(
                    title = stringResource(R.string.settings_alert_quiet_from),
                    icon = Icons.Rounded.Bedtime,
                    tone = accentTone(0),
                    minutesOfDay = state.settings.alerts.quietFromMinutes,
                    onMinutesOfDayChange = { minutes ->
                        viewModel.setAlerts { it.copy(quietFromMinutes = minutes) }
                    },
                )
                GroupTimeItem(
                    title = stringResource(R.string.settings_alert_quiet_to),
                    icon = Icons.Rounded.Bedtime,
                    tone = accentTone(0),
                    minutesOfDay = state.settings.alerts.quietToMinutes,
                    onMinutesOfDayChange = { minutes ->
                        viewModel.setAlerts { it.copy(quietToMinutes = minutes) }
                    },
                )
            }
            GroupSwitchItem(
                title = stringResource(R.string.settings_alert_holidays),
                subtitle = stringResource(R.string.settings_alert_holidays_description),
                icon = Icons.Rounded.BeachAccess,
                tone = accentTone(3),
                checked = state.settings.alerts.skipHolidays,
                onCheckedChange = { on -> viewModel.setAlerts { it.copy(skipHolidays = on) } },
            )
        }
    }

    item(key = "notifications-test") {
        TestAlertGroup(alerts = state.settings.alerts)
    }
}

/**
 * "Проверить" — one sample notification, posted now.
 *
 * The last thing on the page, and the only row here that does something rather
 * than storing something, which is where the sync page puts its own «обновить
 * сейчас» and for the same reason.
 *
 * The outcome is shown in the row's own subtitle rather than in a snackbar: the
 * interesting failure — notifications switched off for the app — needs to stay
 * on screen next to the permission row that fixes it, and a snackbar is gone in
 * four seconds.
 */
@Composable
private fun TestAlertGroup(alerts: AlertPreferences) {
    val context = LocalContext.current
    // Null until the button has been pressed: a row that opens by announcing
    // "отправлено" would be lying about something that has not happened.
    var posted by remember { mutableStateOf<Boolean?>(null) }

    SettingsGroup(title = stringResource(R.string.settings_alerts_test_group)) {
        GroupItem(
            title = stringResource(R.string.settings_alert_test),
            subtitle = when (posted) {
                null -> stringResource(R.string.settings_alert_test_description)
                true -> stringResource(R.string.settings_alert_test_sent)
                false -> stringResource(R.string.settings_alert_test_blocked)
            },
            icon = Icons.Rounded.NotificationsActive,
            tone = if (posted == false) errorTone() else accentTone(2),
            onClick = { posted = AlertPreview.post(context, alerts) },
        )
    }
}

/** Label of a detail level in the segmented picker. */
private val LessonAlertDetail.labelRes: Int
    get() = when (this) {
        LessonAlertDetail.SUBJECT -> R.string.settings_alert_detail_subject
        LessonAlertDetail.FULL -> R.string.settings_alert_detail_full
    }

/**
 * Which mornings the summary may arrive on.
 *
 * Seven chips rather than seven switches: they are one decision with seven
 * parts, and a column of seven rows would be the tallest thing on the page for
 * the setting that matters least.
 *
 * Turning every day off is allowed and is not the same as turning the summary
 * off — the summary stays on with nothing to fire on, which reads as broken.
 * The subtitle says so rather than the code preventing it, because a picker
 * that refuses to let go of the last chip is worse than one that explains.
 */
@Composable
private fun WeekdayRow(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    ChipRow(
        title = stringResource(R.string.settings_alert_morning_days),
        subtitle = if (selected.isEmpty()) {
            stringResource(R.string.settings_alert_morning_days_none)
        } else {
            null
        },
        icon = Icons.Rounded.CalendarMonth,
        tone = accentTone(4),
    ) {
        AlertPreferences.Weekdays.forEach { day ->
            PillChip(
                text = stringResource(weekdayLabelRes(day)),
                selected = day in selected,
                onClick = { onToggle(day) },
            )
        }
    }
}

/** ISO-8601 weekday number to its two-letter Russian abbreviation. */
@StringRes
private fun weekdayLabelRes(day: Int): Int = when (day) {
    1 -> R.string.weekday_short_monday
    2 -> R.string.weekday_short_tuesday
    3 -> R.string.weekday_short_wednesday
    4 -> R.string.weekday_short_thursday
    5 -> R.string.weekday_short_friday
    6 -> R.string.weekday_short_saturday
    else -> R.string.weekday_short_sunday
}

/**
 * How long before the bell, as chips.
 *
 * The one row on this page that kept its buttons, and the reason is that it is
 * the one row that is not asking for a time. "За 15 минут" is a length, and the
 * instrument for a length is not a clock face — a dial that reads 00:15 invites
 * the user to set 09:15 and be told about every lesson nine hours early. Four
 * answers cover it, and they are not a continuum.
 */
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

/** Tile and title on one line, a wrapping row of chips under it. */
@Composable
private fun ChipRow(
    title: String,
    icon: ImageVector,
    tone: AccentTone,
    subtitle: String? = null,
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
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

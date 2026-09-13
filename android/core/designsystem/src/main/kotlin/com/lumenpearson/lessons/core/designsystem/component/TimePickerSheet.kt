package com.lumenpearson.lessons.core.designsystem.component

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.state.formatClockTime
import com.lumenpearson.lessons.core.designsystem.state.hourOfDay
import com.lumenpearson.lessons.core.designsystem.state.minuteOfHour
import com.lumenpearson.lessons.core.designsystem.state.minutesOfDayOf
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone

/**
 * Whether this phone writes `20:00` or `8:00 PM`.
 *
 * Read from the device rather than from the language, and that distinction is
 * the reason this exists at all. The rest of the app prints a school timetable,
 * which is 24-hour on the classroom door whatever the phone says — see
 * `formatHm`. A reminder the user set for themselves is the opposite case: it
 * has to match the clock in their own status bar, because that is what they will
 * compare it against.
 *
 * Keyed on the configuration so that changing the setting in Android and coming
 * back re-reads it; the query goes through a content resolver and is not worth
 * repeating on every recomposition of a list.
 */
@Composable
fun rememberIs24HourClock(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { DateFormat.is24HourFormat(context) }
}

/**
 * Material's clock, in this app's own sheet.
 *
 * A sheet and not `TimePickerDialog`: every other interruption in this app — the
 * server address, the update notes, a day's lessons — arrives from the bottom
 * edge, and a dialog floating in the middle for this one setting would read as
 * borrowed from another app.
 *
 * Both entry modes are offered, because they answer different questions. The
 * dial is faster for "about eight" and hopeless for "07:05"; the keyboard is the
 * reverse. Which one is showing survives a rotation — losing it would be the
 * second time the user has had to find the keyboard button.
 *
 * @param minutesOfDay the value the picker opens on, minutes since midnight.
 * @param onConfirm the chosen time, in the same units. Not called on dismissal:
 *   a picker that commits what the dial happened to be under when the sheet was
 *   swiped away is a picker that changes settings nobody chose.
 */
@Composable
fun LessonsTimePickerSheet(
    minutesOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val view = rememberHapticView()
    val state = rememberTimePickerState(
        initialHour = hourOfDay(minutesOfDay),
        initialMinute = minuteOfHour(minutesOfDay),
        is24Hour = rememberIs24HourClock(),
    )
    // A boolean rather than the mode itself: `TimePickerDisplayMode` is a value
    // class with no saver, and the only two modes this sheet offers are the two
    // this flag names.
    var keyboardEntry by rememberSaveable { mutableStateOf(false) }
    val displayMode = if (keyboardEntry) {
        TimePickerDisplayMode.Input
    } else {
        TimePickerDisplayMode.Picker
    }

    LessonsBottomSheet(onDismissRequest = onDismiss, modifier = modifier, title = title) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (keyboardEntry) TimeInput(state = state) else TimePicker(state = state)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Material draws this one, so the glyph and its spoken name stay in
            // step with whichever mode is on screen.
            TimePickerDialogDefaults.DisplayModeToggle(
                onDisplayModeChange = {
                    LessonsHaptics.tap(view)
                    keyboardEntry = !keyboardEntry
                },
                displayMode = displayMode,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ds_action_cancel))
            }
            Button(
                onClick = {
                    LessonsHaptics.press(view)
                    onConfirm(minutesOfDayOf(state.hour, state.minute))
                },
            ) {
                Text(text = stringResource(R.string.ds_action_save))
            }
        }
    }
}

/**
 * A settings row whose value is a time of day: the time as its trailing value,
 * [LessonsTimePickerSheet] behind the tap.
 *
 * Replaces the row of hour buttons this app used to ask with. Buttons were
 * honest about the hours they offered and silent about the ones they did not —
 * a summary at 07:30 was simply not expressible — and eighteen of them made the
 * tallest thing on the notifications page the setting that matters least.
 *
 * Nothing about it is specific to notifications: it takes a number and hands one
 * back, so any group with a clock-valued setting can use it. A *duration* is not
 * one of those, and should keep its buttons — "за 15 минут до урока" has four
 * sensible answers, and a clock face offers 1 440.
 *
 * @param minutesOfDay current value, minutes since midnight.
 * @param sheetTitle heading of the picker; the row's own title unless the row's
 *   title only makes sense next to the switch above it.
 */
@Composable
fun GroupTimeItem(
    title: String,
    tone: AccentTone,
    minutesOfDay: Int,
    onMinutesOfDayChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    sheetTitle: String = title,
    enabled: Boolean = true,
) {
    // The sheet is composed only while it is open, which is also what makes it
    // open on the current value: it is built fresh from `minutesOfDay` each time
    // rather than holding a copy from whenever the row first appeared.
    var picking by rememberSaveable { mutableStateOf(false) }
    val use24Hour = rememberIs24HourClock()

    GroupItem(
        title = title,
        tone = tone,
        modifier = modifier,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        onClick = { picking = true },
        trailing = {
            Text(
                text = formatClockTime(minutesOfDay, use24Hour),
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        },
    )

    if (picking) {
        LessonsTimePickerSheet(
            minutesOfDay = minutesOfDay,
            onDismiss = { picking = false },
            onConfirm = { chosen ->
                picking = false
                onMinutesOfDayChange(chosen)
            },
            title = sheetTitle,
        )
    }
}

@Preview(name = "GroupTimeItem", showBackground = true)
@Composable
private fun GroupTimeItemPreview() {
    LessonsTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RoundedCardContainer {
                GroupTimeItem(
                    title = "Утренняя сводка в",
                    icon = Icons.Rounded.Schedule,
                    tone = accentTone(4),
                    minutesOfDay = 7 * 60 + 30,
                    onMinutesOfDayChange = {},
                )
                GroupTimeItem(
                    title = "Тишина с",
                    icon = Icons.Rounded.Bedtime,
                    tone = accentTone(0),
                    minutesOfDay = 22 * 60,
                    onMinutesOfDayChange = {},
                )
            }
        }
    }
}

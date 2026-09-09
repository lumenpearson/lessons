package com.lumenpearson.lessons.core.designsystem.state

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FreeBreakfast
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.EventKind
import java.time.Duration

/**
 * Everything the UI needs to draw a [DayState], resolved once.
 *
 * The point is that the hero card, the widget and the notification all render
 * the same state and must not each invent their own colour and wording for
 * "перемена" — one mapping, three consumers.
 */
@Immutable
data class StateVisuals(
    val label: String,
    val detail: String?,
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val icon: ImageVector,
)

/**
 * Maps a domain state onto the current colour scheme and the Russian wording.
 *
 * Composable because both halves are theme- and locale-dependent: the accent has
 * to come from whatever wallpaper-derived scheme is installed, and the label has
 * to come from resources so `values-en/` can replace it.
 */
@Composable
fun DayState.visuals(): StateVisuals {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        // Waiting for the day to start: the calm, non-academic teal, same family
        // as the canteen — nothing is demanded of the reader yet.
        is DayState.BeforeSchool -> StateVisuals(
            label = stringResource(R.string.ds_state_before_school),
            detail = stringResource(R.string.ds_state_first_subject, next.subject),
            accent = scheme.secondary,
            onAccent = scheme.onSecondary,
            container = scheme.secondaryContainer,
            icon = Icons.Rounded.Schedule,
        )

        // A lesson is the app's subject matter, so it gets the primary colour.
        is DayState.InLesson -> StateVisuals(
            label = stringResource(R.string.ds_state_in_lesson),
            detail = current.subject,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            container = scheme.primaryContainer,
            icon = Icons.Rounded.MenuBook,
        )

        // Warm amber: the one moment in the school day that belongs to the pupil.
        is DayState.OnBreak -> StateVisuals(
            label = stringResource(R.string.ds_state_break),
            detail = stringResource(R.string.ds_state_next_subject, next.subject),
            accent = scheme.tertiary,
            onAccent = scheme.onTertiary,
            container = scheme.tertiaryContainer,
            icon = Icons.Rounded.FreeBreakfast,
        )

        is DayState.DuringEvent -> {
            val palette = event.kind.palette()
            StateVisuals(
                label = stringResource(event.kind.labelRes()),
                detail = event.title,
                accent = palette.accent,
                onAccent = palette.onAccent,
                container = palette.container,
                icon = event.kind.icon(),
            )
        }

        // Nothing is running any more, so the state steps back to a neutral
        // surface and lets the homework list below it take the attention.
        is DayState.AfterSchool -> StateVisuals(
            label = stringResource(R.string.ds_state_after_school),
            detail = finishedAt?.let { stringResource(R.string.ds_state_finished_at, it.formatHm()) },
            accent = scheme.onSurfaceVariant,
            onAccent = scheme.surface,
            container = scheme.surfaceVariant,
            icon = Icons.Rounded.School,
        )

        is DayState.DayOff -> StateVisuals(
            label = stringResource(kind.dayOffLabelRes()),
            detail = note ?: when {
                homeworkDay != null -> stringResource(R.string.ds_state_homework_ready)
                else -> stringResource(R.string.ds_state_day_off_detail)
            },
            accent = scheme.tertiary,
            onAccent = scheme.onTertiary,
            container = scheme.tertiaryContainer,
            icon = kind.dayOffIcon(),
        )

        // Muted outline colours: an empty state must not look like a real state.
        is DayState.NoData -> StateVisuals(
            label = stringResource(R.string.ds_state_no_data),
            detail = stringResource(R.string.ds_state_no_data_detail),
            accent = scheme.outline,
            onAccent = scheme.surface,
            container = scheme.surfaceContainerHighest,
            icon = Icons.Rounded.CloudOff,
        )
    }
}

/**
 * The time the hero card counts down to, or `null` for states that simply are.
 *
 * Kept next to [visuals] so the card never has to re-open the sealed hierarchy
 * a second time to find out whether it should draw a countdown at all.
 */
val DayState.countdown: Duration?
    get() = when (this) {
        is DayState.BeforeSchool -> startsIn
        is DayState.InLesson -> endsIn
        is DayState.OnBreak -> endsIn
        is DayState.DuringEvent -> endsIn
        else -> null
    }

/**
 * How far the current interval has run, or `null` when there is no interval.
 *
 * `BeforeSchool` deliberately has none: "morning" has no start boundary the
 * pupil would recognise, so a bar filling from an arbitrary point would be a lie.
 */
val DayState.progressOrNull: Float?
    get() = when (this) {
        is DayState.InLesson -> progress
        is DayState.OnBreak -> progress
        is DayState.DuringEvent -> progress
        else -> null
    }

/** Accent triple for one non-lesson event kind. Private so it stays an implementation detail. */
private data class EventPalette(val accent: Color, val onAccent: Color, val container: Color)

@Composable
private fun EventKind.palette(): EventPalette {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        // Exams are the only state the app is allowed to make you feel something about.
        EventKind.EXAM -> EventPalette(scheme.error, scheme.onError, scheme.errorContainer)
        EventKind.TRIP -> EventPalette(scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer)
        EventKind.CANTEEN,
        EventKind.MEETING,
        EventKind.EVENT,
        -> EventPalette(scheme.secondary, scheme.onSecondary, scheme.secondaryContainer)
    }
}

@StringRes
private fun EventKind.labelRes(): Int = when (this) {
    EventKind.CANTEEN -> R.string.ds_event_canteen
    EventKind.EXAM -> R.string.ds_event_exam
    EventKind.TRIP -> R.string.ds_event_trip
    EventKind.MEETING -> R.string.ds_event_meeting
    EventKind.EVENT -> R.string.ds_event_generic
}

private fun EventKind.icon(): ImageVector = when (this) {
    EventKind.CANTEEN -> Icons.Rounded.Restaurant
    EventKind.EXAM -> Icons.Rounded.EditNote
    EventKind.TRIP -> Icons.Rounded.DirectionsBus
    EventKind.MEETING -> Icons.Rounded.Groups
    EventKind.EVENT -> Icons.Rounded.Event
}

@StringRes
private fun DayKind.dayOffLabelRes(): Int = when (this) {
    DayKind.HOLIDAY -> R.string.ds_state_holiday
    DayKind.REMOTE -> R.string.ds_state_remote_day
    DayKind.SHORTENED -> R.string.ds_state_shortened_day
    DayKind.NORMAL -> R.string.ds_state_day_off
}

private fun DayKind.dayOffIcon(): ImageVector = when (this) {
    DayKind.REMOTE -> Icons.Rounded.Wifi
    DayKind.SHORTENED -> Icons.Rounded.Schedule
    DayKind.HOLIDAY,
    DayKind.NORMAL,
    -> Icons.Rounded.BeachAccess
}

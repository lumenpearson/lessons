package com.lumenpearson.lessons.core.designsystem.state

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FreeBreakfast
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.EventKind
import java.time.Duration

/*
 * Accent slots, named so the mapping below reads as a decision and not as a
 * table of magic numbers. The hues themselves come from the installed scheme,
 * so these survive dark mode and a wallpaper-derived palette.
 */
private const val SlotLesson = 0
private const val SlotBreak = 1
private const val SlotEvent = 2
private const val SlotMorning = 3
private const val SlotTrip = 4
private const val SlotDayOff = 5

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
    val tone: AccentTone,
    val icon: ImageVector,
)

/**
 * Maps a domain state onto the current colour scheme and the Russian wording.
 *
 * Composable because both halves are theme- and locale-dependent: the tone has
 * to come from whatever wallpaper-derived scheme is installed, and the label has
 * to come from resources so `values-en/` can replace it.
 */
@Composable
fun DayState.visuals(): StateVisuals = when (this) {
    // Waiting for the day to start: the calm, non-academic green — nothing is
    // demanded of the reader yet.
    is DayState.BeforeSchool -> StateVisuals(
        label = correctedString(R.string.ds_state_before_school),
        detail = correctedString(R.string.ds_state_first_subject, next.subject),
        tone = accentTone(SlotMorning),
        icon = Icons.Rounded.Schedule,
    )

    // A lesson is the app's subject matter, so it gets the scheme's own hue.
    is DayState.InLesson -> StateVisuals(
        label = correctedString(R.string.ds_state_in_lesson),
        detail = current.subject,
        tone = accentTone(SlotLesson),
        icon = Icons.AutoMirrored.Rounded.MenuBook,
    )

    // Warm amber: the one moment in the school day that belongs to the pupil.
    is DayState.OnBreak -> StateVisuals(
        label = correctedString(R.string.ds_state_break),
        detail = correctedString(R.string.ds_state_next_subject, next.subject),
        tone = accentTone(SlotBreak),
        icon = Icons.Rounded.FreeBreakfast,
    )

    is DayState.DuringEvent -> StateVisuals(
        label = correctedString(event.kind.labelRes()),
        detail = event.title,
        tone = event.kind.tone(),
        icon = event.kind.icon(),
    )

    // Nothing is running any more, so the state steps back to a neutral tile and
    // lets the homework list below it take the attention.
    is DayState.AfterSchool -> StateVisuals(
        label = correctedString(R.string.ds_state_after_school),
        detail = finishedAt?.let { correctedString(R.string.ds_state_finished_at, it.formatHm()) },
        tone = neutralTone(),
        icon = Icons.Rounded.School,
    )

    is DayState.DayOff -> StateVisuals(
        label = correctedString(kind.dayOffLabelRes()),
        detail = note ?: when {
            homeworkDay != null -> correctedString(R.string.ds_state_homework_ready)
            else -> correctedString(R.string.ds_state_day_off_detail)
        },
        tone = accentTone(SlotDayOff),
        icon = kind.dayOffIcon(),
    )

    // Muted: an empty state must not look like a real state.
    is DayState.NoData -> StateVisuals(
        label = correctedString(R.string.ds_state_no_data),
        detail = correctedString(R.string.ds_state_no_data_detail),
        tone = neutralTone(),
        icon = Icons.Rounded.CloudOff,
    )
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

/** The row tone for a non-lesson event; also used by the events list on the Today tab. */
@Composable
fun EventKind.tone(): AccentTone = when (this) {
    // Exams are the only state the app is allowed to make you feel something about.
    EventKind.EXAM -> errorTone()
    EventKind.TRIP -> accentTone(SlotTrip)
    EventKind.CANTEEN,
    EventKind.MEETING,
    EventKind.EVENT,
    -> accentTone(SlotEvent)
}

@StringRes
private fun EventKind.labelRes(): Int = when (this) {
    EventKind.CANTEEN -> R.string.ds_event_canteen
    EventKind.EXAM -> R.string.ds_event_exam
    EventKind.TRIP -> R.string.ds_event_trip
    EventKind.MEETING -> R.string.ds_event_meeting
    EventKind.EVENT -> R.string.ds_event_generic
}

/** Also used outside the hero card: the events group on the Today tab draws the same glyphs. */
fun EventKind.icon(): ImageVector = when (this) {
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

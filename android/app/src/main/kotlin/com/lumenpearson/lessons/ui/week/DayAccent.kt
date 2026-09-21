package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.model.DayOffReason
import java.time.DayOfWeek

/**
 * Why a date is drawn the way it is, when it is drawn as anything but ordinary.
 *
 * The calendar had one answer for four different facts: an empty cell. A
 * Saturday with nothing on it, the week between two quarters, the whole of
 * July, and 9 May all looked alike, and the only way to tell them apart was to
 * open each one and read the sentence inside. Which of them a reader is looking
 * at changes what they do next, so it has to be visible from the grid.
 *
 * Ordered from weakest to strongest: where a day could be two of these — the
 * first of January is both a public holiday and the winter break — the
 * stronger one is what is drawn, because it is the one that explains the
 * neighbours as well.
 */
internal enum class DayAccent {
    /** Nothing to say. An ordinary day, with or without lessons. */
    NONE,

    /** A weekend the timetable simply has nothing on. Not a decision anybody made. */
    WEEKEND,

    /** Inside the year, in none of its terms: the holidays between two of them. */
    BETWEEN_TERMS,

    /** Before the year opened or after it closed — the summer, mostly. */
    OUT_OF_YEAR,

    /** A statutory non-working day. One date, and it is worth the loudest colour. */
    PUBLIC_HOLIDAY,
}

/**
 * The accent this day carries.
 *
 * A day the server has not sent yet ([WeekDayUi.day] null) gets [DayAccent.NONE]
 * rather than a guess: a blank cell in an unsynced month is «not loaded», and
 * painting it as the holidays would be inventing an answer out of a gap.
 */
internal fun WeekDayUi.accent(): DayAccent {
    val schoolDay = day ?: return DayAccent.NONE
    return when (schoolDay.offReason) {
        DayOffReason.PUBLIC_HOLIDAY -> DayAccent.PUBLIC_HOLIDAY
        DayOffReason.OUT_OF_YEAR -> DayAccent.OUT_OF_YEAR
        DayOffReason.BETWEEN_TERMS -> DayAccent.BETWEEN_TERMS
        null -> if (!schoolDay.hasLessons && date.dayOfWeek.isWeekend()) {
            DayAccent.WEEKEND
        } else {
            DayAccent.NONE
        }
    }
}

private fun DayOfWeek.isWeekend(): Boolean =
    this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

/** Container and text for one accent, resolved against the live scheme. */
internal data class AccentColors(val container: Color, val content: Color)

@Composable
@ReadOnlyComposable
internal fun DayAccent.colors(): AccentColors {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        DayAccent.NONE -> AccentColors(scheme.rowContainer, scheme.onSurface)
        // A weekend is the quietest of the four: it is the normal shape of a
        // week rather than news, and four of them a month drawn loudly would
        // make the month look like a holiday.
        DayAccent.WEEKEND -> AccentColors(
            scheme.surfaceVariant.copy(alpha = 0.45f).compositeOver(scheme.surface),
            scheme.onSurfaceVariant,
        )
        DayAccent.BETWEEN_TERMS -> AccentColors(
            scheme.tertiaryContainer.copy(alpha = 0.55f).compositeOver(scheme.surface),
            scheme.onTertiaryContainer,
        )
        // Fainter than the gap between terms, and deliberately: the summer is
        // two whole months, and a colour that reads well on nine days is a
        // wall on sixty.
        DayAccent.OUT_OF_YEAR -> AccentColors(
            scheme.tertiaryContainer.copy(alpha = 0.28f).compositeOver(scheme.surface),
            scheme.onSurfaceVariant,
        )
        DayAccent.PUBLIC_HOLIDAY -> AccentColors(
            scheme.errorContainer,
            scheme.onErrorContainer,
        )
    }
}

/**
 * Where a day sits in a run of days carrying the same accent.
 *
 * This is what makes a stretch read as one thing rather than as five things
 * that happen to be the same colour: the outer ends of a run are rounded and
 * the inside is square, so a week of holidays draws as one bar with the dates
 * on it. A run is broken by any change of accent, and by nothing else — a run
 * that reaches the end of a grid row is still «middle», which is what makes it
 * continue on the next line instead of stopping at the edge of the week.
 */
internal data class RunPosition(val first: Boolean, val last: Boolean)

/** [RunPosition] for every day in an ordered list, by comparing neighbours. */
internal fun List<DayAccent>.runPositions(): List<RunPosition> = mapIndexed { index, accent ->
    // `NONE` never joins anything: an ordinary Tuesday next to an ordinary
    // Wednesday is two days, and a bar drawn across a working week would say
    // something about it that is not true.
    if (accent == DayAccent.NONE) {
        RunPosition(first = true, last = true)
    } else {
        RunPosition(
            first = index == 0 || this[index - 1] != accent,
            last = index == lastIndex || this[index + 1] != accent,
        )
    }
}

/** The cell's shape: rounded where the run ends, square where it carries on. */
internal fun RunPosition.shape(): Shape {
    val round = (LessonsShapeTokens.Row as? RoundedCornerShape) ?: RoundedCornerShape(12.dp)
    val flat = RoundedCornerShape(0.dp)
    return when {
        first && last -> round
        first -> RoundedCornerShape(
            topStart = round.topStart,
            bottomStart = round.bottomStart,
            topEnd = flat.topEnd,
            bottomEnd = flat.bottomEnd,
        )
        last -> RoundedCornerShape(
            topStart = flat.topStart,
            bottomStart = flat.bottomStart,
            topEnd = round.topEnd,
            bottomEnd = round.bottomEnd,
        )
        else -> flat
    }
}

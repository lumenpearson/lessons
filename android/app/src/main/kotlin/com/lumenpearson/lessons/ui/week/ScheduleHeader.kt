package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding

/** What «назад» announces, for the unit this press actually moves. */
private val PeriodStep.previousRes: Int
    get() = when (this) {
        PeriodStep.DAY -> R.string.day_previous
        PeriodStep.WEEK -> R.string.week_previous
        PeriodStep.MONTH -> R.string.month_previous
    }

/** What «вперёд» announces. @see previousRes */
private val PeriodStep.nextRes: Int
    get() = when (this) {
        PeriodStep.DAY -> R.string.day_next
        PeriodStep.WEEK -> R.string.week_next
        PeriodStep.MONTH -> R.string.month_next
    }

/** What «вернуться к сегодня» announces. @see previousRes */
private val PeriodStep.currentRes: Int
    get() = when (this) {
        PeriodStep.DAY -> R.string.day_current
        PeriodStep.WEEK -> R.string.week_current
        PeriodStep.MONTH -> R.string.month_current
    }

/**
 * The page title and the three period controls.
 *
 * The controls used to be actions in a top app bar. They are here because there
 * is no top app bar any more, and because the top-right corner of a 6.7 inch
 * phone was a poor place for the one pair of buttons on this screen that
 * anybody presses repeatedly.
 */
@Composable
internal fun ScheduleHeader(
    periodLabel: String,
    termLabel: String?,
    yearLabel: String,
    /** What one press of an arrow moves — and so what it announces. */
    step: PeriodStep,
    showTodayAction: Boolean,
    onToday: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickYear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two rows, not one, and that is the whole fix. While the title shared a
    // row with the chip and three icon buttons it was given what was left —
    // about eight characters at 411 dp — so «Календарь» wrapped after
    // «Календар» and the subtitle ran to three lines. Nothing about it looks
    // wrong in the code: `weight(1f)` is the correct way to share a row, and
    // the row was simply asked to hold more than it has.
    //
    // On its own row the title has the screen's width, which is more than any
    // title here will ever need, and the controls below have the width they
    // need rather than the width that is left.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    ) {
        ScreenHeader(
            title = correctedString(R.string.week_title),
            // «Октябрь 2026 · 1 четверть». One line rather than two: the term
            // qualifies the period rather than standing beside it, and a second
            // line pushes the grid down on every phone for a word. It is asked
            // for explicitly now — the header wraps a subtitle by default,
            // because `DocsScreen` puts a whole sentence there.
            subtitle = listOfNotNull(periodLabel, termLabel).joinToString(" · "),
            singleLineSubtitle = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Level with the header's own text, which insets itself by 8.
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The school year, and the way into the years either side of it. A
            // chip rather than a third pair of arrows: the arrows step the
            // *period*, which is a week here and a month there, and a second
            // pair meaning «year» beside them is two controls that look the
            // same and are not.
            PillChip(
                text = yearLabel,
                onClick = onPickYear,
            )
            Spacer(Modifier.weight(1f))
            // The slot is always here; only the button inside it comes and
            // goes. «Вернуться к сегодня» is offered only when it would do
            // something — a button for a period that already contains today is
            // noise — but while the button itself was the thing that appeared,
            // both arrows slid sideways by 48 dp every time the reader stepped
            // into or out of this week. A control that moves under the thumb
            // between two presses is worse than a gap.
            Box(
                modifier = Modifier.size(IconButtonSlot),
                contentAlignment = Alignment.Center,
            ) {
                if (showTodayAction) {
                    IconButton(onClick = onToday) {
                        Icon(
                            imageVector = Icons.Rounded.Today,
                            contentDescription = correctedString(step.currentRes),
                        )
                    }
                }
            }
            // From [step], not from `R.string.week_*`. These three are the only
            // part of the header a sighted reader never sees, which is how they
            // went on saying «неделя» in four of the five modes long after the
            // text above them learned to name the day or the month.
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = correctedString(step.previousRes),
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = correctedString(step.nextRes),
                )
            }
        }
    }
}

/**
 * The side of the square an [IconButton] occupies, so a slot can be kept for
 * one that is not there.
 *
 * Material's own minimum touch target, which is what `IconButton` sizes itself
 * to; named here rather than written as `48.dp` at the one place that needs it,
 * because the number is only correct as long as it is the same number.
 */
private val IconButtonSlot: Dp = 48.dp

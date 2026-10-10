package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.InlineGap
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

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
 * The page title, and a panel holding the period between the arrows that step it.
 *
 * The controls used to be actions in a top app bar. They are here because there
 * is no top app bar any more, and because the top-right corner of a 6.7 inch
 * phone was a poor place for the one pair of buttons on this screen that
 * anybody presses repeatedly.
 *
 * The period sits *between* its arrows (#416). It used to be the title's
 * subtitle, a line above the row that stepped it, and that row held the
 * «2026/27» chip at one end and the arrows at the other with nothing between —
 * a band the owner circled on the emulator. Now the panel is what the eye
 * meets under the title: «‹», the period with its term and school year under
 * it, «›». The year is the panel's middle rather than a chip of its own, and
 * pressing it still opens the year picker.
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(InlineGap),
    ) {
        // The title on a row of its own width, so «Календарь» never shares
        // what is left of a row and wraps after «Календар» again — the defect
        // this header was split into rows for. «Сегодня» rides at the row's
        // end and drops under the title when both do not fit, at a large
        // font on a narrow phone, rather than squeezing it. Nothing else is on
        // this row, so its coming and going moves nothing under the thumb: the
        // arrows are in the panel below and stay where they are.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenHeader(
                title = correctedString(R.string.week_title),
                // The title's own width rather than the row's: the header fills
                // whatever it is given, and given the row it would leave the
                // pill nowhere but the next line on every phone.
                modifier = Modifier.width(IntrinsicSize.Max),
            )
            if (showTodayAction) {
                // «Сегодня» on the screen, and the unit it returns to for a
                // reader who cannot see it: «Текущая неделя», «Текущий месяц».
                // From [step] for the same reason as the arrows below.
                val announced = correctedString(step.currentRes)
                PillChip(
                    text = correctedString(R.string.nav_today),
                    icon = Icons.Rounded.Today,
                    onClick = onToday,
                    modifier = Modifier.semantics { contentDescription = announced },
                )
            }
        }
        PeriodPanel(
            periodLabel = periodLabel,
            detail = listOfNotNull(termLabel, yearLabel).joinToString(" · "),
            step = step,
            onPrevious = onPrevious,
            onNext = onNext,
            onPickYear = onPickYear,
        )
    }
}

/**
 * «‹ period ›» in a tray of the view switcher's skin, which sits under it.
 *
 * Grows rather than clips (the owner's choice for overflow): at a large font or
 * on a narrow phone the period and its detail wrap, the panel gets taller, and
 * the arrows stay 48 dp squares at its ends. Rounded by `Group` rather than as
 * a capsule, so that a taller panel stays a rounded rectangle with the radius
 * of every other container instead of a pill whose ends swell with its height;
 * at its one-line height of 56 dp the two are the same shape, the switcher's.
 */
@Composable
private fun PeriodPanel(
    periodLabel: String,
    detail: String,
    step: PeriodStep,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickYear: () -> Unit,
) {
    val pickYear = correctedString(R.string.week_year_pick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PanelMinHeight)
            .clip(LessonsShapeTokens.Group)
            .background(MaterialTheme.colorScheme.rowContainer)
            .padding(PanelInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // From [step], not from `R.string.week_*`. These two are the only part
        // of the header a sighted reader never hears, which is how they went on
        // saying «неделя» in four of the five modes long after the text beside
        // them learned to name the day or the month.
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Rounded.ChevronLeft,
                contentDescription = correctedString(step.previousRes),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(LessonsShapeTokens.Pill)
                .clickable(onClickLabel = pickYear, role = Role.Button, onClick = onPickYear)
                .padding(horizontal = InlineGap, vertical = PanelInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Wraps, never marquees: the owner chose a panel that grows over a
            // line that scrolls, so every word of the period is on screen. A
            // number stays with the word after it, so a week wraps at its dash
            // — «12 октября —» over «18 октября» — and never as «… — 18» over a
            // lone «октября», which is how it broke at twice the font size.
            Text(
                text = remember(periodLabel) { periodLabel.replace(NumberThenSpace, "$1\u00A0") },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    // The year is already in the text; the arrow only says that
                    // pressing it opens something.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(DropDownSize),
                )
            }
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = correctedString(step.nextRes),
            )
        }
    }
}

/**
 * The panel's one-line height: a 48 dp touch target in the tray's 4 dp, the
 * same as the view switcher under it, so the two read as one family.
 */
private val PanelMinHeight: Dp = 56.dp

/** The tray's padding, the switcher's `contentPadding`. */
private val PanelInset: Dp = 4.dp

/** A number and the space after it, which [PeriodPanel] makes non-breaking. */
private val NumberThenSpace = Regex("""(\d) """)

/** The drop-down arrow beside the year: the size of the detail line's text. */
private val DropDownSize: Dp = 18.dp

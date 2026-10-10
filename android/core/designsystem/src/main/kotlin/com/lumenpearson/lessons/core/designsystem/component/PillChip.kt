package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.text.DataLine
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.emphasised

/**
 * A pill's two padding variants, on the 4 dp grid: 16 x 8 for a tappable pill
 * (its touch target past 48 dp comes from [minimumInteractiveComponentSize]
 * instead of this padding), 12 x 4 for a static one.
 *
 * `internal` rather than `private` so [HomeworkRow]'s attachment pill — which
 * cannot call [PillChip] itself without changing its icon size and text style,
 * both fixed here — reads the same two pairs instead of a third, hand-copied
 * set that could drift from these the day either changes.
 */
internal val TappableChipPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
internal val StaticChipPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/**
 * The smallest status carrier in the design system: "замена", "отменён", "сейчас",
 * and — with [onClick] — the calendar's filters and its year, the diary's
 * pupils, and the choices in the sync and notification settings.
 *
 * Exists instead of `AssistChip`/`FilterChip` because most of these are pure
 * labels: a chip that invites a tap that does nothing is worse than a label, and
 * a chip's minimum touch target would make every lesson row 16 dp taller.
 *
 * @param selected the chip is the current one — the day in view, the lesson
 *   running now. It drives the default colours, so a selected chip fills with
 *   the primary colour and one glance finds the current day in a row of seven;
 *   and it sets the label bold, which is the part that survives when a caller
 *   brings colours of its own.
 * @param onClick when non-null the chip becomes a real, ripple-clipped target.
 */
@Composable
fun PillChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    containerColor: Color = when {
        selected -> MaterialTheme.colorScheme.primary
        onClick != null -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.secondaryContainer
    },
    contentColor: Color = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        onClick != null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    },
    border: BorderStroke? = null,
) = PillChip(
    text = DataLine.of(text),
    modifier = modifier,
    icon = icon,
    selected = selected,
    onClick = onClick,
    containerColor = containerColor,
    contentColor = contentColor,
    border = border,
)

/**
 * A chip whose label is the app's words and data — «Схема 0017», «Урок 3» —
 * where only the data scrolls if it does not fit (#251). See [DataLine].
 */
@Composable
fun PillChip(
    text: DataLine,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    // An interactive but unselected chip is a filter that is *off*, so it drops
    // to a neutral surface; a plain label keeps the tonal container that makes it
    // readable as a status against a card.
    containerColor: Color = when {
        selected -> MaterialTheme.colorScheme.primary
        onClick != null -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.secondaryContainer
    },
    contentColor: Color = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        onClick != null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    },
    border: BorderStroke? = null,
) {
    // Clipping before the click keeps the ripple inside the capsule; Surface's
    // own clip happens too late for a modifier handed in from outside.
    //
    // minimumInteractiveComponentSize gives a tappable chip Material's 48 dp
    // touch target, and it does that as layout, not only as touch: the chip
    // reports at least 48 x 48 dp to whatever places it and centres its
    // capsule in that slot. The capsule is not enlarged and draws exactly as
    // before, but a row of tappable chips is 48 dp tall instead of 32, so
    // chip rows read looser — a line of filters 16 dp taller, and wrapped
    // lines that InlineGap spaced 8 dp apart now 24 apart, capsule to capsule.
    // A static chip is never pressed, gets no slot and stays as short as its
    // text, so a row that mixes the two has to line them up by their centres.
    //
    // `clickable`'s own hit testing already reaches 48 dp around a smaller
    // node in this Compose, so the slot is not what makes the chip pressable;
    // the target is asked for through the documented Material API rather
    // than left to rest on that detail.
    val interaction = when (onClick) {
        null -> Modifier
        else -> Modifier
            .minimumInteractiveComponentSize()
            .clip(LessonsShapeTokens.Pill)
            .clickable(onClick = onClick)
    }

    Surface(
        modifier = modifier.then(interaction),
        shape = LessonsShapeTokens.Pill,
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(if (onClick != null) TappableChipPadding else StaticChipPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            MarqueeText(
                line = text,
                style = MaterialTheme.typography.labelSmall.emphasised(selected, resting = FontWeight.Medium),
            )
        }
    }
}

@Preview(name = "PillChip", showBackground = true)
@Composable
private fun PillChipPreview() {
    LessonsTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip(text = "замена", icon = Icons.Rounded.SwapHoriz)
            PillChip(text = "Пн", selected = true, onClick = {})
            PillChip(
                text = "отменён",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            PillChip(
                text = "сейчас",
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

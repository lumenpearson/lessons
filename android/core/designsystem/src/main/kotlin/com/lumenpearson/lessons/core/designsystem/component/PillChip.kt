package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.emphasised

/**
 * The smallest status carrier in the design system: "замена", "отменён", "сейчас",
 * and — with [onClick] — the day selector at the top of the week screen.
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
    val interaction = when (onClick) {
        null -> Modifier
        else -> Modifier
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
            // A tappable chip gets a taller box so it clears a usable touch target.
            modifier = Modifier.padding(
                horizontal = if (onClick != null) 14.dp else 10.dp,
                vertical = if (onClick != null) 8.dp else 4.dp,
            ),
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
                text = text,
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

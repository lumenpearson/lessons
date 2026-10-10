package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.text.DataLine
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.InlineGap
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding

/**
 * The quiet label above a group.
 *
 * Indented to line up with the text inside the group's first row rather than
 * with the screen margin, and muted rather than bold: the groups are the objects
 * on the screen, the labels only say what each one is.
 *
 * @param titleColor muted by default. The first-run screens pass the palette's
 *   primary, which is what Essentials does on its own setup pages: there the
 *   labels are the only structure on a page the user has never seen, so they are
 *   allowed to carry colour, and a settings page read every week is not.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) = SectionHeader(
    title = DataLine.of(title),
    modifier = modifier,
    subtitle = subtitle,
    actionLabel = actionLabel,
    onActionClick = onActionClick,
    titleColor = titleColor,
)

/**
 * A header whose title is the app's words and data — «Домашнее задание на
 * понедельник, 5 октября» — where only the data scrolls if it does not fit
 * (#251). See [DataLine].
 */
@Composable
fun SectionHeader(
    title: DataLine,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val hasAction = actionLabel != null && onActionClick != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = ScreenPadding,
                // TextButton pads its own content 12 dp at each end
                // (ButtonDefaults.TextButtonContentPadding) — a fixed
                // measure of the button, not of this row — so when the
                // action is shown the row's own end has to give up those
                // 12 dp for the label and the arrow to land exactly on
                // ScreenPadding themselves. With no action there is no
                // button eating into it, so the end is ScreenPadding, same
                // as the start. A flat ScreenPadding on both sides puts the
                // ink 12 dp short of the switcher, the chips and the weekday
                // strip it is meant to match — bounds do not mean ink, for a
                // button.
                end = if (hasAction) ScreenPadding - TextButtonEndPadding else ScreenPadding,
                top = InlineGap,
                bottom = InlineGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MarqueeText(
                line = title,
                // titleMedium, the weight Essentials gives every section label:
                // large enough to structure the page, muted enough that the
                // groups under it stay the objects on screen.
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
            )
            if (subtitle != null) {
                MarqueeText(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp),
                )
            }
        }
    }
}

/**
 * Material's own `TextButtonContentPadding` end value (1.5.0-alpha24,
 * `Button.kt`'s `TextButtonHorizontalPadding`). Named here, not read off
 * `ButtonDefaults`, because what this file needs is the number, not a
 * `PaddingValues` to destructure every time; a future Material bump that
 * changes this would need this constant moved anyway, and it would be found
 * exactly as a corner literal is — by the number no longer matching.
 */
private val TextButtonEndPadding = 12.dp

@Preview(name = "SectionHeader", showBackground = true)
@Composable
private fun SectionHeaderPreview() {
    LessonsTheme {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionHeader(
                title = "Расписание на сегодня",
                subtitle = "5 уроков, один отменён",
                actionLabel = "Вся неделя",
                onActionClick = {},
            )
            SectionHeader(title = "Домашнее задание")
        }
    }
}

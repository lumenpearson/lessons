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
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme

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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MarqueeText(
                text = title,
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

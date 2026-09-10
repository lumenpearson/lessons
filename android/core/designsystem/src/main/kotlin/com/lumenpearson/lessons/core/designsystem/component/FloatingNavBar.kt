package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.floatingContainer

/**
 * The bottom bar as a pill that floats above the content instead of a slab
 * welded to the bottom edge.
 *
 * Inset from all three edges so the scrolling page visibly continues underneath
 * it, which is what stops a screen of rounded cards from looking like it was cut
 * off at the bottom.
 */
@Composable
fun FloatingNavBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
        shape = LessonsShapeTokens.Floating,
        color = MaterialTheme.colorScheme.floatingContainer,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * One destination.
 *
 * The selected item is marked by a filled pill behind its icon rather than by
 * tinting the icon alone: at a glance across a dark bar, a shape change is legible
 * where a colour change is not.
 */
@Composable
fun RowScope.FloatingNavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val indicator = if (selected) scheme.secondaryContainer else Color.Transparent
    val contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant

    Column(
        modifier = modifier
            .weight(1f)
            .clip(LessonsShapeTokens.Floating)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(LessonsShapeTokens.Pill)
                .background(indicator)
                .padding(horizontal = 18.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(name = "FloatingNavBar", showBackground = true)
@Composable
private fun FloatingNavBarPreview() {
    LessonsTheme {
        FloatingNavBar {
            FloatingNavBarItem(
                selected = true,
                onClick = {},
                icon = Icons.Rounded.Today,
                label = "Сегодня",
            )
            FloatingNavBarItem(
                selected = false,
                onClick = {},
                icon = Icons.Rounded.CalendarViewWeek,
                label = "Неделя",
            )
            FloatingNavBarItem(
                selected = false,
                onClick = {},
                icon = Icons.Rounded.MenuBook,
                label = "Задания",
            )
            FloatingNavBarItem(
                selected = false,
                onClick = {},
                icon = Icons.Rounded.Settings,
                label = "Настройки",
            )
        }
    }
}

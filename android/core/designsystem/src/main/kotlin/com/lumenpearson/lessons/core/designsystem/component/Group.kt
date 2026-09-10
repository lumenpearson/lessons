package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.GroupInset
import com.lumenpearson.lessons.core.designsystem.theme.GroupRowSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.groupContainer
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

private val TileSize: Dp = 40.dp

/**
 * The container every screen in this app is built from: one rounded block
 * holding a stack of rows.
 *
 * The rows keep a hairline gap rather than a divider between them, so each one
 * reads as its own pill while the block still reads as one group — that is the
 * whole grammar of the app, and it is defined once here.
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LessonsShapeTokens.Group,
        color = MaterialTheme.colorScheme.groupContainer,
    ) {
        Column(
            modifier = Modifier.padding(GroupInset),
            verticalArrangement = Arrangement.spacedBy(GroupRowSpacing),
            content = content,
        )
    }
}

/**
 * The raw row surface, for the handful of rows that need their own layout
 * (homework text, the sync-interval chips) but must still look like every other
 * row in the group.
 */
@Composable
fun GroupRow(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.rowContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LessonsShapeTokens.Row,
        color = container,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = verticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/**
 * Icon tile, title, muted subtitle, optional trailing control.
 *
 * @param tone the row's own hue. Every row in a group gets a different one; it
 *   is the fastest way to find a known row in a list you have read before.
 */
@Composable
fun GroupItem(
    title: String,
    tone: AccentTone,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // Disabled rows lose the tile colour rather than gaining a grey overlay: a
    // washed-out pastel still reads as "there is a colour here" and confuses.
    val rowTone = if (enabled) tone else AccentTone(scheme.surfaceContainerHighest, scheme.outline)

    GroupRow(
        modifier = modifier,
        onClick = onClick?.takeIf { enabled },
    ) {
        if (icon != null) {
            AccentIconTile(icon = icon, tone = rowTone)
        }
        RowText(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            titleColor = if (enabled) scheme.onSurface else scheme.outline,
        )
        trailing?.invoke(this)
    }
}

/** A [GroupItem] whose trailing control is a switch; the whole row toggles it. */
@Composable
fun GroupSwitchItem(
    title: String,
    tone: AccentTone,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    GroupItem(
        title = title,
        tone = tone,
        modifier = modifier,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/** A [GroupItem] that leads somewhere: an optional value, then a chevron. */
@Composable
fun GroupLinkItem(
    title: String,
    tone: AccentTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
) {
    GroupItem(
        title = title,
        tone = tone,
        modifier = modifier,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick,
        trailing = {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/** The two-line text block of a row, so the two lines never drift apart. */
@Composable
fun RowText(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleMaxLines: Int = 2,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The circular colour tile, with whatever the caller wants inside it. */
@Composable
fun AccentTile(
    tone: AccentTone,
    modifier: Modifier = Modifier,
    size: Dp = TileSize,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(LessonsShapeTokens.Tile)
            .background(tone.container),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** The common case: one glyph on the tile, in the tile's own contrasting colour. */
@Composable
fun AccentIconTile(
    icon: ImageVector,
    tone: AccentTone,
    modifier: Modifier = Modifier,
    size: Dp = TileSize,
) {
    AccentTile(tone = tone, modifier = modifier, size = size) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tone.content,
            modifier = Modifier.size(size / 2),
        )
    }
}

@Preview(name = "Group", showBackground = true)
@Composable
private fun GroupPreview() {
    LessonsTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(title = "Оформление")
            GroupCard {
                GroupSwitchItem(
                    title = "Цвета из обоев",
                    subtitle = "Палитра приложения подстраивается под обои",
                    icon = Icons.Rounded.Palette,
                    tone = accentTone(0),
                    checked = true,
                    onCheckedChange = {},
                )
                GroupSwitchItem(
                    title = "Чёрная тема",
                    subtitle = "Полностью чёрный фон в тёмной теме",
                    icon = Icons.Rounded.DarkMode,
                    tone = accentTone(5),
                    checked = false,
                    onCheckedChange = {},
                )
                GroupLinkItem(
                    title = "Показывать учителя",
                    subtitle = "Имя учителя в строке урока",
                    icon = Icons.Rounded.Person,
                    tone = accentTone(3),
                    value = "Включено",
                    onClick = {},
                )
                GroupItem(
                    title = "Открыть неделю",
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    tone = accentTone(1),
                    onClick = {},
                )
            }
        }
    }
}

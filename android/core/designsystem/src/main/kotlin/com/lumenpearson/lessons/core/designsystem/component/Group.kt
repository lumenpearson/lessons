package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.LocalControlCentre
import com.lumenpearson.lessons.core.designsystem.modifier.centreInRoot
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.GroupRowSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.designsystem.theme.rowSelectedContainer

private val TileSize: Dp = 40.dp

/** Padding of a row built on [ListItem]; Essentials' own 16 × 8. */
private val RowPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * The container every screen in this app is built from: one rounded block
 * holding a stack of rows.
 *
 * A port of `RoundedCardContainer` from
 * [Essentials](https://github.com/sameerasw/essentials), and the single most
 * load-bearing thing this app borrows from it. The container is *only a clip*:
 * it has no fill of its own, the rows inside it are square, and the 2 dp gaps
 * between them show the page through. That is what makes a group read as one
 * slab with soft ends rather than as a pile of separate cards, and it is why
 * neither the rows nor this container round their own corners.
 */
@Composable
fun RoundedCardContainer(
    modifier: Modifier = Modifier,
    spacing: Dp = GroupRowSpacing,
    cornerRadius: Dp = 24.dp,
    containerColor: Color = Color.Transparent,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * The raw row surface, for the rows that need their own layout — a lesson, a
 * homework note, the sync-interval chips — but must still look like every other
 * row in the group.
 *
 * Rectangular by design: see [RoundedCardContainer].
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
    val view = rememberHapticView()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = container,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable {
                            LessonsHaptics.press(view)
                            onClick()
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = verticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/**
 * Icon tile, title, muted subtitle, optional trailing control.
 *
 * Built on Material's [ListItem] the way every settings row in Essentials is,
 * so that touch target, text baselines and the disabled state come from the
 * platform rather than from four screens' worth of hand-rolled padding.
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
    trailing: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()
    // Disabled rows lose the tile colour rather than gaining a grey overlay: a
    // washed-out pastel still reads as "there is a colour here" and confuses.
    val rowTone = if (enabled) tone else AccentTone(scheme.surfaceContainerHighest, scheme.outline)

    val leading: (@Composable () -> Unit)? = icon?.let { { AccentIconTile(icon = it, tone = rowTone) } }
    val supporting: (@Composable () -> Unit)? = subtitle?.let {
        {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val headline: @Composable () -> Unit = {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) scheme.onSurface else scheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val colors = ListItemDefaults.colors(containerColor = scheme.rowContainer)

    // A row with nothing to tap uses the plain overload rather than a clickable
    // one that has been disabled: the disabled clickable is announced as a
    // button that cannot be pressed, which is a lie about a row that was never
    // meant to be pressed at all.
    if (onClick == null) {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            colors = colors,
            content = headline,
        )
    } else {
        ListItem(
            onClick = {
                LessonsHaptics.press(view)
                onClick()
            },
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = RowPadding,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            colors = colors,
            content = headline,
        )
    }
}

/**
 * A [GroupItem] whose trailing control is a switch; the whole row toggles it.
 *
 * Built on `ListItem`'s checkable overload rather than on [GroupItem] with a
 * switch dropped into its trailing slot — the same choice Essentials makes in
 * `IconToggleItem`. It is not cosmetic: the checkable overload is what gives
 * the row the switch role and its on/off state in the accessibility tree, so a
 * screen reader says "Чёрная тема, выключено" instead of reading a label and an
 * unrelated control next to it.
 */
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
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()
    val rowTone = if (enabled) tone else AccentTone(scheme.surfaceContainerHighest, scheme.outline)

    // The switch sits against the right edge; the row it belongs to is the
    // width of the screen. Anything that starts an effect where the finger was
    // needs the first of those, not the second — see ControlCentre.
    val controlCentre = LocalControlCentre.current
    var switchCentre by remember { mutableStateOf(Offset.Unspecified) }

    ListItem(
        checked = checked && enabled,
        onCheckedChange = {
            LessonsHaptics.press(view)
            if (switchCentre.isSpecified) controlCentre?.report(switchCentre)
            onCheckedChange(it)
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = RowPadding,
        leadingContent = icon?.let { { AccentIconTile(icon = it, tone = rowTone) } },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            // No click of its own: the row already owns the gesture, and two
            // overlapping targets is how one tap toggles twice.
            Switch(
                checked = checked && enabled,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.centreInRoot { switchCentre = it },
            )
        },
        // The switched-on colour is named rather than left to the library's
        // default for the checked state: the default is chosen to read against
        // Material's own list background, and these rows sit on `surfaceBright`
        // inside a group, where it is nearly invisible in a dark scheme.
        colors = ListItemDefaults.colors(
            containerColor = scheme.rowContainer,
            selectedContainerColor = scheme.rowSelectedContainer,
        ),
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) scheme.onSurface else scheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * A group's own call to action, filled and full width, as the last row of the
 * group it belongs to.
 *
 * Straight out of the Essentials settings screen, where "Check for updates"
 * closes the updates group the same way. A row with a chevron says "there is
 * more to read here"; this says "press this and something happens now", and the
 * two are worth telling apart when they are eight pixels apart.
 *
 * It sits on the group's own row colour rather than on the page, so that the
 * button floats inside the slab with the rows instead of breaking it in two.
 */
@Composable
fun GroupActionItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val view = rememberHapticView()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.rowContainer,
    ) {
        Button(
            onClick = {
                LessonsHaptics.press(view)
                onClick()
            },
            enabled = enabled && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .height(ActionRowHeight),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(10.dp))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Height of a filled action inside a group; Essentials' own 52 dp. */
private val ActionRowHeight: Dp = 52.dp

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
            }
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
            RoundedCardContainer {
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

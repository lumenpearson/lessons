package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme

/**
 * One destination of the toolbar in tabbed mode.
 *
 * @param badge draws Essentials' red dot over the icon; for "there is something
 *   new behind this tab" without spending a label on it.
 */
data class ToolbarItem(
    val icon: ImageVector,
    val label: String,
    val badge: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The button drawn beside the pill.
 *
 * @param badge as on [ToolbarItem]: a dot over the icon, for "there is something
 *   here" without a label to say what.
 */
data class ToolbarAction(
    val icon: ImageVector,
    val contentDescription: String,
    val badge: Boolean = false,
    val onClick: () -> Unit,
)

/** Width of an icon-only item, and the height of every item. */
private val ItemSize: Dp = 48.dp

/** Extra width the selected item grows by to fit its label. */
private val LabelWidth: Dp = 80.dp

/** Gap between two items while the toolbar is expanded. */
private val ItemGap: Dp = 8.dp

/** Above this text scale the label is dropped rather than squeezed. */
private const val LabelFontScaleLimit = 1.25f

/**
 * Below this width four slots plus a label do not fit.
 *
 * Essentials drops the label below 400 dp, which is wider than most phones in
 * portrait: on a 360 dp screen — a Pixel 8, say — that hides the label
 * permanently, so the expanding pill that is the whole point of the component
 * never appears on the device it was written for. Four slots need
 * 3×48 + 3×8 spacing + 48 + 80 = 296 dp inside 32 dp of margin, so 328 dp is the
 * real floor and 330 is it with a little air.
 *
 * The action button counts as a slot. It is drawn outside the pill but it is
 * drawn on the same row, and a pill that fits only because the thing beside it
 * was not counted does not fit.
 */
private const val CompactScreenWidthDp = 330

/** Diameter of the badge dot. */
private val BadgeSize: Dp = 8.dp

/** Longest a title may be in the toolbar's standard mode before it marquees. */
private val TitleWidthRange = 100.dp..250.dp

/**
 * The bar at the bottom: a vibrant pill floating over the content, where the
 * selected destination grows out of the row as an inverted pill carrying its
 * label.
 *
 * A port of `EssentialsFloatingToolbar` from
 * [Essentials](https://github.com/sameerasw/essentials) (MIT) — the spring, the
 * colour inversion, the 48/80 dp geometry and both of its modes: a row of tabs,
 * or a back button with a title. Three things are deliberately different, each
 * of them a bug in the original:
 *
 *  * **The empty FAB slot.** Essentials always passes `floatingActionButton`,
 *    handing it `{}` when there is no button. `HorizontalFloatingToolbar` still
 *    lays out the slot it was given, so an empty one reserves the button's width
 *    and pins the pill off-centre with a hole beside it. Here the slot is only
 *    passed when there is something to put in it.
 *  * **The gaps between collapsed items.** The spacer between two items animates
 *    to 8 dp whenever the item is not the last one — including while the toolbar
 *    is collapsed and every unselected item has animated to zero width. The
 *    collapsed pill therefore keeps `(n − 1) × 8 dp` of dead space inside it.
 *    Here the gap collapses with the items.
 *  * **The label threshold.** See [CompactScreenWidthDp].
 *
 * @param items tabbed mode: pass these and [selectedIndex].
 * @param title standard mode: pass this with [onBackClick].
 * @param expanded false collapses every unselected item into the selected one.
 * @param action the button beside the pill — Essentials' `fabAction`. It is
 *   outside the pill rather than in it because it is not a peer of what is
 *   inside: in tabbed mode the pill is where you are and the button is where you
 *   can go, and on a sub-page the pill is the way back and the button is the one
 *   thing that page can do.
 * @param floatingActionButton the same slot, for a caller that needs to draw the
 *   button itself. [action] is the shorthand and wins if both are given.
 */
@Composable
fun LessonsFloatingToolbar(
    modifier: Modifier = Modifier,
    items: List<ToolbarItem> = emptyList(),
    selectedIndex: Int = -1,
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    expanded: Boolean = true,
    scrollBehavior: FloatingToolbarScrollBehavior? = null,
    action: ToolbarAction? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val actionButton: (@Composable () -> Unit)? = when {
        action != null -> {
            { ToolbarActionButton(action) }
        }

        else -> floatingActionButton
    }

    val slots = items.size + if (actionButton != null) 1 else 0
    val hideLabel = fontScale > LabelFontScaleLimit ||
        (screenWidth < CompactScreenWidthDp && slots > 3)

    val colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
        toolbarContentColor = scheme.onSurface,
        toolbarContainerColor = scheme.primary,
    )

    // The toolbar wraps its content, so it needs a full-width parent to be
    // centred in; without one it sits wherever its parent's alignment puts it.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val content: @Composable RowScope.() -> Unit = {
            if (onBackClick != null) {
                BackAndTitle(title = title, onBackClick = onBackClick)
            } else {
                items.forEachIndexed { index, item ->
                    ToolbarTab(
                        item = item,
                        selected = index == selectedIndex,
                        isLast = index == items.lastIndex,
                        expanded = expanded,
                        hideLabel = hideLabel,
                    )
                }
            }
        }

        // Two call sites rather than one with a nullable argument: the overload
        // without the slot is what keeps a toolbar with no action button centred.
        if (actionButton != null) {
            HorizontalFloatingToolbar(
                expanded = expanded,
                colors = colors,
                scrollBehavior = scrollBehavior,
                floatingActionButton = actionButton,
                content = content,
            )
        } else {
            HorizontalFloatingToolbar(
                expanded = expanded,
                colors = colors,
                scrollBehavior = scrollBehavior,
                content = content,
            )
        }
    }
}

/** One tab: an icon that grows into an inverted pill with a label when selected. */
@Composable
private fun ToolbarTab(
    item: ToolbarItem,
    selected: Boolean,
    isLast: Boolean,
    expanded: Boolean,
    hideLabel: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()
    val visible = expanded || selected

    val itemWidth by animateDpAsState(
        targetValue = if (visible) ItemSize else 0.dp,
        animationSpec = toolbarSpring(),
        label = "toolbar_item_width",
    )
    val labelWidth by animateDpAsState(
        targetValue = if (selected && !hideLabel) LabelWidth else 0.dp,
        animationSpec = toolbarSpring(),
        label = "toolbar_label_width",
    )
    // Collapses with the item beside it; see the note on LessonsFloatingToolbar.
    val gap by animateDpAsState(
        targetValue = if (expanded && !isLast) ItemGap else 0.dp,
        animationSpec = toolbarSpring(),
        label = "toolbar_item_gap",
    )

    if (itemWidth <= 0.dp && !selected) return

    IconButton(
        onClick = {
            LessonsHaptics.press(view)
            item.onClick()
        },
        modifier = Modifier
            .width(itemWidth + labelWidth)
            .height(ItemSize),
        colors = if (selected) {
            IconButtonDefaults.filledIconButtonColors(
                contentColor = scheme.primary,
                containerColor = scheme.background,
            )
        } else {
            IconButtonDefaults.iconButtonColors(
                contentColor = scheme.background,
                containerColor = scheme.primary,
            )
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (selected) scheme.primary else scheme.background,
                    modifier = Modifier.size(24.dp),
                )
                if (item.badge) {
                    Box(
                        modifier = Modifier
                            .size(BadgeSize)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(scheme.error),
                    )
                }
            }
            if (selected && !hideLabel) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
            }
        }
    }

    if (!isLast) {
        Spacer(Modifier.width(gap))
    }
}

/**
 * Essentials' `fabAction` button: tonal, square-ish, and flat.
 *
 * No elevation on purpose — the pill beside it has none either, and a shadow
 * under one of the two would read as the button hovering above the bar rather
 * than sitting next to it.
 */
@Composable
private fun ToolbarActionButton(action: ToolbarAction) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()

    FloatingActionButton(
        onClick = {
            LessonsHaptics.press(view)
            action.onClick()
        },
        containerColor = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Box {
            Icon(
                imageVector = action.icon,
                contentDescription = action.contentDescription,
                modifier = Modifier.size(24.dp),
            )
            if (action.badge) {
                Box(
                    modifier = Modifier
                        .size(BadgeSize)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(scheme.error),
                )
            }
        }
    }
}

/** Standard mode: the same inverted pill, holding a back button and a title. */
@Composable
private fun BackAndTitle(
    title: String?,
    onBackClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()

    IconButton(
        onClick = {
            LessonsHaptics.press(view)
            onBackClick()
        },
        modifier = Modifier.size(ItemSize),
        colors = IconButtonDefaults.filledIconButtonColors(
            contentColor = scheme.primary,
            containerColor = scheme.background,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            // Named, not null. The title beside it is a separate node, so a
            // screen reader announcing this button announced nothing at all.
            contentDescription = stringResource(R.string.ds_action_back),
            modifier = Modifier.size(24.dp),
        )
    }
    if (title != null) {
        Spacer(Modifier.width(ItemGap))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.background,
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = TitleWidthRange.start, max = TitleWidthRange.endInclusive)
                .padding(horizontal = 8.dp)
                .basicMarquee(),
        )
    }
}

/**
 * The one spring the toolbar animates with.
 *
 * Bouncy and slow, which is what gives the selected pill its overshoot; every
 * width in the component shares it so they cannot arrive at different times.
 */
private fun toolbarSpring() = spring<Dp>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
)

@Preview(name = "Floating toolbar", showBackground = true)
@Composable
private fun FloatingToolbarPreview() {
    LessonsTheme {
        LessonsFloatingToolbar(
            selectedIndex = 0,
            items = listOf(
                ToolbarItem(Icons.Rounded.Today, "Сегодня") {},
                ToolbarItem(Icons.Rounded.CalendarViewWeek, "Неделя") {},
                ToolbarItem(Icons.AutoMirrored.Rounded.MenuBook, "Задания", badge = true) {},
            ),
            action = ToolbarAction(Icons.Rounded.Settings, "Настройки") {},
        )
    }
}

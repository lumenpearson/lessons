package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme

/** One destination in [FloatingNavBar]. */
data class NavBarItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** Width of an icon-only item, and the height of every item. */
private val ItemSize = 48.dp

/** Extra width the selected item grows by to fit its label. */
private val LabelWidth = 80.dp

/** Above this text scale the label is dropped rather than squeezed. */
private const val LabelFontScaleLimit = 1.25f

/**
 * Below this width four items plus a label do not fit.
 *
 * The reference drops the label below 400dp, which is wider than most phones in
 * portrait — on a 360dp screen that hid the label permanently, so the expanding
 * pill never appeared on the device it was written for. Four items need
 * 3×48 + 3×8 spacing + 48 + 80 = 296dp inside 32dp of margin, so 328dp is the
 * real floor.
 */
private const val CompactScreenWidthDp = 330

/**
 * The bottom bar: a vibrant pill floating over the content, where the selected
 * destination grows out of the row as an inverted pill carrying its label.
 *
 * Modelled on `EssentialsFloatingToolbar` from sameerasw/essentials (MIT), down
 * to the spring, the colour inversion and the rules for dropping the label. The
 * mechanics are Material 3's own `HorizontalFloatingToolbar`; what makes it read
 * as Essentials rather than as a stock navigation bar is that only one label is
 * ever shown, and that the selected item inverts the bar's colours instead of
 * merely tinting an icon.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingNavBar(
    items: List<NavBarItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val hideLabel =
        fontScale > LabelFontScaleLimit ||
            (screenWidth < CompactScreenWidthDp && items.size > 3)

    // The toolbar wraps its content, so it needs a full-width parent to be
    // centred in. Its floatingActionButton slot is left off entirely rather than
    // passed an empty lambda: an empty slot still reserves the width of the
    // button that is not there, which pinned the pill to the left edge with a
    // hole beside it.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                toolbarContentColor = scheme.onSurface,
                toolbarContainerColor = scheme.primary,
            ),
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val labelWidth by animateDpAsState(
                    targetValue = if (selected && !hideLabel) LabelWidth else 0.dp,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                    label = "nav_label_width_$index",
                )

                IconButton(
                    onClick = item.onClick,
                    modifier = Modifier
                        .width(ItemSize + labelWidth)
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
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) scheme.primary else scheme.background,
                            modifier = Modifier.size(24.dp),
                        )
                        if (selected && !hideLabel) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = scheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (index < items.lastIndex) {
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Preview(name = "FloatingNavBar", showBackground = true)
@Composable
private fun FloatingNavBarPreview() {
    LessonsTheme {
        FloatingNavBar(
            selectedIndex = 0,
            items = listOf(
                NavBarItem(Icons.Rounded.Today, "Сегодня") {},
                NavBarItem(Icons.Rounded.CalendarViewWeek, "Неделя") {},
                NavBarItem(Icons.Rounded.MenuBook, "Задания") {},
                NavBarItem(Icons.Rounded.Settings, "Настройки") {},
            ),
        )
    }
}

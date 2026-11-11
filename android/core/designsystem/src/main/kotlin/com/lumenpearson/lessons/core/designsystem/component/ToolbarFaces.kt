package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * The pill itself: Material's floating toolbar container — its shape, its
 * colour, its height and the shadow it has beside a button — without the rest
 * of Material's toolbar.
 *
 * The rest is `minimumInteractiveBalancedPadding`, which pads the content by
 * the interactive alignment lines it finds inside it, and it cannot live with
 * a pill that morphs. The back button leaving slides its line off to one side
 * with it, the padding turned that distance into height, and the pill came back
 * from settings to the tabs as a tall oval that stayed one until something made
 * it measure again (#264, filmed by the owner). It had already been the cause
 * of #240. Built here, the pill is as tall as its content and never less than
 * [FloatingToolbarDefaults.ContainerSize], whatever passes through it.
 */
@Composable
internal fun PillContainer(
    modifier: Modifier,
    colors: FloatingToolbarColors,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = FloatingToolbarDefaults.ContainerShape
    val elevation = FloatingToolbarDefaults.ContainerExpandedElevationWithFab
    Row(
        modifier = modifier
            .graphicsLayer {
                shadowElevation = elevation.toPx()
                this.shape = shape
                clip = true
            }
            .heightIn(min = FloatingToolbarDefaults.ContainerSize)
            .background(color = colors.toolbarContainerColor, shape = shape)
            .padding(PillContentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.toolbarContentColor) {
            content()
        }
    }
}

/**
 * What the pill shows: everything a face needs, so that the face on its way out
 * draws what it drew (#264). [key] is what makes two faces different ones —
 * the back button is one face whatever its title says, and a set of tabs is
 * one face for as long as it is the same set.
 */
@Immutable
internal sealed interface PillFace {
    val key: Any

    /** How deep the page is; a deeper face arrives from the right. */
    val depth: Int
}

@Immutable
internal data class BackFace(
    val title: String?,
    val onBackClick: () -> Unit,
    val titleMax: Dp,
    override val depth: Int,
) : PillFace {
    override val key: Any get() = BackFaceKey
}

@Immutable
internal data class TabsFace(
    val items: List<ToolbarItem>,
    val selectedIndex: Int,
    val expanded: Boolean,
    val hideLabel: Boolean,
    val scrollable: Boolean,
    val hasAction: Boolean,
    val reorderable: Boolean,
    val reordering: Boolean,
    val onReorderingChange: (Boolean) -> Unit,
    val onReorder: (order: List<Int>) -> Unit,
    val labelMax: Dp,
    override val depth: Int,
) : PillFace {
    // The set, not the list: the same tabs in a new order are the same face.
    // Keyed by the order, a confirmed rearrangement was a new face, and the old
    // row faded out over the new one — every icon seemed to move again after
    // the drop, which the owner filmed on 3 October.
    override val key: Any get() = items.mapTo(HashSet()) { it.label }
}

/**
 * The tabs' face, with the widest a label may be worked out for them: on a
 * scrolling row, what the scroll window spares beside one icon; otherwise,
 * what the whole row can spare.
 */
@Composable
internal fun tabsFace(
    items: List<ToolbarItem>,
    selectedIndex: Int,
    expanded: Boolean,
    hideLabel: Boolean,
    scrollable: Boolean,
    hasAction: Boolean,
    reorderable: Boolean,
    reordering: Boolean,
    onReorderingChange: (Boolean) -> Unit,
    onReorder: (order: List<Int>) -> Unit,
    screenWidth: Dp,
    tablet: Boolean,
    depth: Int,
): TabsFace = TabsFace(
    items = items,
    selectedIndex = selectedIndex,
    expanded = expanded,
    hideLabel = hideLabel,
    scrollable = scrollable,
    hasAction = hasAction,
    reorderable = reorderable,
    reordering = reordering,
    onReorderingChange = onReorderingChange,
    onReorder = onReorder,
    labelMax = textCap(
        screenWidth = screenWidth,
        tablet = tablet,
        spare = if (scrollable) {
            scrollingItemsMaxWidth(hasAction) - PillPadding * 2 - ItemSize
        } else {
            spareLabelWidth(screenWidth, items.size, hasAction)
        },
    ),
    depth = depth,
)

/** The back button's face is one face, whatever the title beside it. */
private const val BackFaceKey = "back"

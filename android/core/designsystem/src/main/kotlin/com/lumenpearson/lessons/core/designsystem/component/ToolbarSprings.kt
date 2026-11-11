package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import com.lumenpearson.lessons.core.designsystem.theme.springSpec

/**
 * The one spring the toolbar animates with — at the reader's motion speed, and a
 * snap with animations off, like every spring here: none of them read the
 * motion settings until #246, so the bar went on springing with the switch off.
 *
 * Without a bounce. It used to overshoot, which gave the selected tab a
 * flourish of its own and pushed every tab beside it past its place and back:
 * once the selection slid as a pill (#258), each neighbour shook as the pill
 * arrived, as if struck, and the owner filmed it («дергаются после
 * затрагивания их таблеткой»). Every width in the component shares it so they
 * cannot arrive at different times — all but the one that changes on its own,
 * the label going as the tabs start to be arranged, which is
 * [toolbarSettleSpring]'s.
 */
internal fun MotionSettings.toolbarSpring() = springSpec<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The spring the selected tab's label goes on as the arranging mode opens.
 *
 * [toolbarSpring]'s pace without its bounce. The long press that opens the mode
 * is also the start of a drag (#181), and the drag counts in slots of one
 * pitch: a label that overshot on its way out made its tab narrower than an
 * icon for a moment, which carried a neighbour already sliding into the gap
 * past the end of the row.
 */
internal fun MotionSettings.toolbarSettleSpring() = springSpec<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/**
 * The spring the pill's width follows while it morphs between its two modes.
 *
 * Less bouncy than [toolbarSpring]: the whole bar overshooting its width reads
 * as the bar wobbling, where one tab overshooting reads as the tab landing.
 */
internal fun MotionSettings.toolbarSizeSpring() = springSpec<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The spring a tab slides on while its neighbour is dragged past it.
 *
 * Stiffer and flatter than [toolbarSpring]: this one is chasing a finger that
 * is still moving, so an overshoot is a tab that goes past the gap it is
 * supposed to be opening and comes back — which reads as the row arguing with
 * the drag.
 */
internal fun MotionSettings.toolbarOffsetSpring() = springSpec<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** Cross-fade and slide of the two toolbar modes. */
internal const val ModeFadeMillis = 180
internal const val ModeSlideMillis = 260

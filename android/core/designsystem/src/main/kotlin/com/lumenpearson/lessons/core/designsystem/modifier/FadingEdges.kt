package com.lumenpearson.lessons.core.designsystem.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How far the fade reaches in from each edge, unless a caller says otherwise. */
val FadingEdgeWidth: Dp = 12.dp

/**
 * Dissolves the left and right edges of an element instead of cutting them off.
 *
 * Content that is wider than its box — a scrolling label, a row of chips, a long
 * line of text — otherwise ends at a hard vertical line, which reads as damage
 * rather than as "there is more of this". Fading the edges says the same thing
 * quietly.
 *
 * This fades to *transparent*, not to a colour. Painting a gradient in the
 * container's own colour is the obvious approach and it is wrong the moment the
 * container is anything but a flat fill: a selected segment, a tinted card, a
 * pill that inverts when chosen, a wallpaper-derived surface, a theme the user
 * switches while looking at it. An alpha mask removes the pixels, so whatever is
 * behind shows through and the fade is correct in all of those cases without
 * being told what it sits on.
 *
 * Implemented as a `DstIn` blend against an offscreen layer, which is what makes
 * "remove the pixels" possible: the layer is drawn, then multiplied by a
 * gradient whose alpha runs 0 → 1 → 1 → 0 across the width.
 *
 * @param width how far the fade reaches in from each edge. Clamped to half the
 *   element, so a box narrower than two fades still gets a sensible one instead
 *   of gradient stops in the wrong order.
 */
fun Modifier.fadingEdges(width: Dp = FadingEdgeWidth): Modifier = composed {
    val widthPx = with(LocalDensity.current) { width.toPx() }

    this
        // Offscreen, or the blend below would multiply against whatever has
        // already been painted on the screen rather than against this element.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.width <= 0f) return@drawWithContent
            val fade = widthPx.coerceAtMost(size.width / 2f)
            val stop = fade / size.width
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    stop to Color.Black,
                    1f - stop to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

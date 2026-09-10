package com.lumenpearson.lessons.core.designsystem.modifier

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/** One sweep of the highlight, in milliseconds. Essentials' own timing. */
private const val SweepMillis = 1000

/** How far the gradient travels, in pixels, before it restarts. */
private const val SweepDistance = 1000f

/** Length of the highlight band. */
private const val BandLength = 500f

/**
 * The loading shimmer from `sameerasw/essentials`
 * `ui/components/modifiers/ShimmerModifier.kt`.
 *
 * Draws *behind* the content rather than over it, which is what makes it usable
 * as the background of a placeholder row: the row keeps its shape and its
 * corners, and only the fill moves.
 *
 * The reference builds a throwaway list of `Color.Unspecified` and then discards
 * it; that is dead code, and it is not carried over here.
 */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = SweepDistance,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SweepMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    val colors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    drawBehind {
        drawRect(
            brush = Brush.linearGradient(
                colors = colors,
                start = Offset(offset - BandLength, offset - BandLength),
                end = Offset(offset, offset),
            ),
        )
    }
}

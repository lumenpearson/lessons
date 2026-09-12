package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone

/**
 * The school bell, swinging, for the tile of the hero card during a break.
 *
 * It replaces a static glyph for the one state where the app has something to
 * celebrate, and it is the only moving thing on the screen — which is the point.
 * A break is the state a pupil checks most and the one that is over soonest, so
 * it is worth being unmistakable from across a desk.
 *
 * **The depth is real, not a drawn highlight.** The bell rotates about the point
 * it hangs from on two axes at once: `rotationZ` for the swing and `rotationY`
 * for the turn away from the viewer, with a camera distance short enough for the
 * perspective divide to be visible. So the far side of the rim genuinely
 * narrows as it goes back, which no amount of shading on a flat shape gets
 * right. The clapper runs on the same period a quarter-beat behind, because a
 * clapper that moves *with* the bell is a bell that cannot ring.
 *
 * @param ringing false parks it upright — the composable stays in the tree
 *   across the state change so the swing settles rather than disappearing.
 */
@Composable
fun SchoolBell(
    tone: AccentTone,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    ringing: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "school_bell")

    val swing by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = beat(),
        label = "bell_swing",
    )
    // A quarter of a period behind the dome. The lag is what reads as weight.
    val clapper by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = beat(offsetMillis = SwingMillis / 4),
        label = "bell_clapper",
    )

    val angle = if (ringing) swing * SwingDegrees else 0f
    val clapperShift = if (ringing) clapper else 0f

    Box(
        modifier = modifier
            .size(size)
            .clip(LessonsShapeTokens.Row)
            .background(tone.container)
            .padding(size * BellInset),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The pivot is where the bell hangs, not its middle.
                    transformOrigin = TransformOrigin(0.5f, 0.08f)
                    rotationZ = angle
                    // Half the swing about the vertical axis, which is what turns
                    // a rocking picture into an object with a far side.
                    rotationY = angle * DepthRatio
                    cameraDistance = CameraDistance
                },
        ) {
            drawBell(color = tone.content, clapperShift = clapperShift)
        }
    }
}

/** One swing, there and back, easing at both ends the way a pendulum does. */
private fun beat(offsetMillis: Int = 0): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = SwingMillis, easing = FastOutSlowInEasing),
    repeatMode = RepeatMode.Reverse,
    initialStartOffset = StartOffset(offsetMillis),
)

/** How far the bell swings, in degrees either side of upright. */
private const val SwingDegrees = 16f

/** How much of the swing is rotation away from the viewer rather than across it. */
private const val DepthRatio = 0.55f

/**
 * How far the camera sits from the plane the bell is drawn on.
 *
 * Three quarters of Compose's own default of 8, so the perspective divide is
 * actually visible rather than the near-orthographic projection a distant camera
 * gives — the whole reason this component rotates about Y at all.
 *
 * The unit is *not* pixels, which is why nothing here scales it by the density:
 * `GraphicsLayerScope.cameraDistance` is passed straight to `RenderNode`, whose
 * camera distance is density-independent (the View layer divides by `densityDpi`
 * on the way in, and its default of `1280 * density` px is exactly this 8). It
 * used to be multiplied by the density anyway, which on a three-times screen put
 * the camera at 36 — four and a half times further away than the default instead
 * of a quarter closer — and flattened the depth this component is built around,
 * differently on every phone.
 */
private const val CameraDistance = 6f

/** Half a period; the spec reverses, so a full swing is twice this. */
private const val SwingMillis = 620

/** Breathing room between the bell and the edge of its tile, as a fraction. */
private const val BellInset = 0.18f

/**
 * The bell itself: a dome flaring into a rim, a loop to hang it by, and a
 * clapper under the mouth.
 *
 * Drawn rather than shipped as a vector because it has to be one shape in the
 * accent colour of whatever tone it is handed — a drawable would need a tint
 * list per hue, and there are six hues times two schemes.
 */
private fun DrawScope.drawBell(color: Color, clapperShift: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f

    // The loop it hangs from.
    drawCircle(
        color = color,
        radius = w * 0.09f,
        center = Offset(cx, h * 0.10f),
        style = Stroke(width = w * 0.06f),
    )

    // The dome: two mirrored curves from the shoulders down to the mouth.
    val dome = Path().apply {
        moveTo(cx - w * 0.33f, h * 0.72f)
        cubicTo(
            cx - w * 0.33f, h * 0.36f,
            cx - w * 0.20f, h * 0.19f,
            cx, h * 0.19f,
        )
        cubicTo(
            cx + w * 0.20f, h * 0.19f,
            cx + w * 0.33f, h * 0.36f,
            cx + w * 0.33f, h * 0.72f,
        )
        close()
    }
    drawPath(path = dome, color = color)

    // The rim, wider than the dome so the mouth reads as an opening.
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - w * 0.44f, h * 0.70f),
        size = Size(w * 0.88f, h * 0.13f),
        cornerRadius = CornerRadius(w * 0.06f),
    )

    // The clapper, swinging inside the mouth a beat behind the dome.
    drawCircle(
        color = color,
        radius = w * 0.08f,
        center = Offset(cx + clapperShift * w * 0.16f, h * 0.92f),
    )
}

@Preview(name = "School bell", showBackground = true)
@Composable
private fun SchoolBellPreview() {
    LessonsTheme {
        SchoolBell(tone = accentTone(2), modifier = Modifier.padding(16.dp))
    }
}

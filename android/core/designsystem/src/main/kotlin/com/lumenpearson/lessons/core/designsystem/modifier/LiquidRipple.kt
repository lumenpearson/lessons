package com.lumenpearson.lessons.core.designsystem.modifier

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import org.intellij.lang.annotations.Language

/**
 * The AGSL displacement from `sameerasw/essentials`
 * `ui/modifiers/LiquidRippleModifier.kt`, with four defects fixed.
 *
 * The physics are the reference's and worth keeping: every pixel asks how far it
 * is from the point that was touched, waits that long divided by the wave speed,
 * and then rides a decaying sine outward along the line from the origin. Two of
 * them, the second a little later, a little slower and a little smaller, which is
 * what turns a single ring into something that looks like liquid.
 *
 * What changed, and why:
 *
 *  - **`uResolution` is now read.** The reference declares it and never uses it,
 *    which is not merely untidy: SkSL strips a uniform nothing references, and
 *    `setFloatUniform` for a name that is no longer in the compiled program
 *    throws. Here it does the job it was presumably meant for — clamping the
 *    sample so a pixel displaced past the edge reads the edge rather than the
 *    transparent nothing outside the layer, which drew as a dark rim running
 *    around the screen ahead of the wave.
 *  - **The origin pixel no longer produces NaN.** `normalize` of a zero vector
 *    is undefined, and the pixel exactly under the finger is a zero vector. The
 *    direction is now a division guarded at one pixel, so the middle of the
 *    ripple stands still instead of turning into whatever the driver makes of a
 *    NaN coordinate.
 *  - **The highlight respects alpha.** The reference adds a flat white to a
 *    premultiplied colour, which for a transparent pixel produces a colour
 *    brighter than its own alpha — an invalid premultiplied value that shows up
 *    as grey haze over anything see-through. Scaling it by alpha and clamping
 *    there keeps it a highlight on what is drawn rather than a fog over what is
 *    not.
 *  - **It stops when the wave does.** See [RippleMillis].
 */
@Language("AGSL")
private const val LiquidRippleShader = """
    uniform shader inputShader;
    uniform float2 uResolution;
    uniform float2 uOrigin;
    uniform float uTime;
    uniform float uAmplitude;
    uniform float uFrequency;
    uniform float uDecay;
    uniform float uSpeed;
    uniform float uReverse;
    uniform float uFarthest;

    half4 main(float2 fragCoord) {
        float2 toward = fragCoord - uOrigin;
        float reach = length(toward);

        // How far this pixel is along the front's path. Outward, that is its own
        // distance from the origin, so near pixels move first. Inward, it is
        // what is left to the far corner, so the order reverses and the ring
        // closes on the origin instead of leaving it.
        float travelled = mix(reach, uFarthest - reach, uReverse);

        // The wavefront has not arrived here yet while this is zero, which is
        // what makes the ring expand rather than the whole screen pulse at once.
        float time = max(0.0, uTime - travelled / uSpeed);

        float wave1 = uAmplitude * sin(uFrequency * time) * exp(-uDecay * time);

        float subTime = max(0.0, time - 0.22);
        float wave2 = (uAmplitude * 0.55) * sin(uFrequency * 1.15 * subTime) *
            exp(-(uDecay * 0.8) * subTime);

        float totalWave = wave1 + wave2;

        // Guarded at a pixel: at the origin itself this is very nearly zero, so
        // that pixel is left where it is instead of being normalized by nothing.
        // Also flipped when the front runs inward, so pixels are pulled toward
        // the origin rather than pushed away from it.
        float2 outward = toward / max(reach, 1.0);
        float2 direction = mix(outward, -outward, uReverse);
        float2 samplePos = clamp(fragCoord + totalWave * direction, float2(0.0), uResolution);

        half4 color = inputShader.eval(samplePos);
        half highlight = half(0.16 * (totalWave / max(1.0, uAmplitude)));
        half3 lit = clamp(color.rgb + highlight * color.a, half3(0.0), half3(color.a));
        return half4(lit, color.a);
    }
"""

/**
 * How long the ripple runs.
 *
 * The reference says three seconds, and with its own decay of 4.5 the wave is
 * mathematically dead after about one: `exp(-4.5 × 1)` is a hundredth of the
 * amplitude, which on a 32 dp wave is a third of a pixel. The other two seconds
 * are a full-screen runtime shader recomputing an invisible displacement for
 * every pixel, sixty times a second, over the whole of the rest of the app —
 * and on the reference's own video you can see the frame rate pay for it.
 *
 * This is the time the last wavefront needs instead: long enough for the ring to
 * cross a tall phone at 1400 dp per second and for its tail to die there, and
 * not a frame longer.
 */
private const val RippleMillis = 1500

/**
 * A wave running out from a point, displacing whatever is drawn under it.
 *
 * Fired rather than driven: [trigger] is a counter a caller increments, and each
 * increment starts one pass from [origin]. That is the reference's own protocol
 * and it is the right one here, because the thing being answered is a tap — a
 * boolean would have no way to say "again".
 *
 * Requires Android 13 (AGSL runtime shaders); below that, and whenever [enabled]
 * is false, this is `Modifier` and nothing is drawn differently.
 *
 * @param trigger increment to fire; zero and below never fires, so the effect
 *   does not run itself on the first composition.
 * @param origin in this layer's own coordinates. Unspecified ripples from the
 *   middle, which is the honest fallback for a caller that has no point to give.
 * @param amplitude how far a pixel is pushed at the crest of the first wave.
 * @param frequency crests per second at the origin.
 * @param decay how fast a crest dies; larger is a shorter, sharper ring.
 * @param speed how fast the front travels.
 * @param reverse runs the front inward instead of outward: it starts at the far
 *   corners and closes on [origin], pulling pixels toward it rather than pushing
 *   them away. For answering "no" with the same gesture played backwards.
 */
fun Modifier.liquidRipple(
    trigger: Int,
    origin: Offset,
    enabled: Boolean = true,
    durationMillis: Int = RippleMillis,
    amplitude: Dp = 32.dp,
    frequency: Float = 12f,
    decay: Float = 4.5f,
    speed: Dp = 1400.dp,
    reverse: Boolean = false,
): Modifier = composed {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return@composed Modifier
    }
    val density = LocalDensity.current
    val seconds = durationMillis / 1000f
    val time = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        time.snapTo(0f)
        // Linear on purpose: the shader's own `exp` is the shape of the thing.
        // An eased clock would be a second curve fighting the first.
        time.animateTo(seconds, tween(durationMillis, easing = LinearEasing))
        time.snapTo(0f)
    }

    LiquidRippleLayer.of(
        time = time,
        seconds = seconds,
        origin = origin,
        amplitudePx = with(density) { amplitude.toPx() },
        frequency = frequency,
        decay = decay,
        speedPx = with(density) { speed.toPx() },
        reverse = reverse,
    )
}

/** Distance from [from] to the furthest corner of a box of [size]. */
private fun farthestCorner(from: Offset, size: Size): Float = maxOf(
    hypot(from.x, from.y),
    hypot(size.width - from.x, from.y),
    hypot(from.x, size.height - from.y),
    hypot(size.width - from.x, size.height - from.y),
)

/** The graphics layer itself, kept behind an API guard lint can see. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LiquidRippleLayer {

    @Composable
    fun of(
        time: Animatable<Float, *>,
        seconds: Float,
        origin: Offset,
        amplitudePx: Float,
        frequency: Float,
        decay: Float,
        speedPx: Float,
        reverse: Boolean,
    ): Modifier {
        // Compiled once, and survivable if it does not compile. A `RuntimeShader`
        // built from source that some driver's SkSL rejects throws from its
        // constructor, and a constructor throwing inside a composition takes the
        // app down — for an ornament. Null here is a shell that simply does not
        // ripple.
        val shader = remember { runCatching { RuntimeShader(LiquidRippleShader) }.getOrNull() }
            ?: return Modifier

        return Modifier.graphicsLayer {
            val now = time.value
            renderEffect = if (now > 0f && now < seconds) {
                // Unspecified rather than the reference's `!= Offset.Zero`,
                // which cannot tell a caller that means the top-left corner
                // from one that has not said.
                val centre = if (origin.isSpecified) {
                    origin
                } else {
                    Offset(size.width / 2f, size.height / 2f)
                }
                shader.setFloatUniform("uResolution", size.width, size.height)
                shader.setFloatUniform("uOrigin", centre.x, centre.y)
                shader.setFloatUniform("uTime", now)
                shader.setFloatUniform("uAmplitude", amplitudePx)
                shader.setFloatUniform("uFrequency", frequency)
                shader.setFloatUniform("uDecay", decay)
                shader.setFloatUniform("uSpeed", speedPx)
                shader.setFloatUniform("uReverse", if (reverse) 1f else 0f)
                // The distance the inward front has to cover before it reaches
                // the origin. Measured rather than assumed, because the layer is
                // whatever size it is and a front that starts short of the
                // corner leaves them untouched.
                shader.setFloatUniform("uFarthest", farthestCorner(centre, size))
                RenderEffect.createRuntimeShaderEffect(shader, "inputShader")
                    .asComposeRenderEffect()
            } else {
                null
            }
        }
    }
}

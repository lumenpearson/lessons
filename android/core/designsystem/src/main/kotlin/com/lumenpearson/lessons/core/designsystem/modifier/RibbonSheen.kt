package com.lumenpearson.lessons.core.designsystem.modifier

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import org.intellij.lang.annotations.Language

/**
 * How much of the ribbon's depth a device can actually be given.
 *
 * Three levels rather than a boolean, because the two things this screen wants
 * arrived in Android at different times and there is a real design between
 * them. A single «шейдеры включены» would have meant either nothing below
 * Android 13 or a check written out at four call sites, each free to disagree
 * with the others about what "supported" means.
 */
enum class RibbonDepthLevel {
    /** Nothing beyond the flat card: the reader switched it off. */
    NONE,

    /**
     * Gradients and the tilt, and no shader.
     *
     * Everything from `minSdk` up to Android 12 inclusive. A vertical gradient
     * and a perspective rotation are both plain Compose, so the card still has
     * a near edge and a far one — it simply does not have a light running
     * across it.
     */
    GRADIENT,

    /** The full thing: AGSL, so the running row is lit where the clock is. */
    SHADER,
    ;

    val drawsShader: Boolean get() = this == SHADER
    val drawsDepth: Boolean get() = this != NONE
}

/**
 * Which level this device gets.
 *
 * Pure, and takes the API level rather than reading it, so the rule can be
 * tested at every version this app runs on instead of at whichever one the test
 * runner happens to emulate. Android 13 is where `RuntimeShader` arrives;
 * `minSdk` is 26, so a third of the ladder is genuinely below it.
 */
fun ribbonDepthLevel(sdkInt: Int, enabled: Boolean): RibbonDepthLevel = when {
    !enabled -> RibbonDepthLevel.NONE
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> RibbonDepthLevel.SHADER
    else -> RibbonDepthLevel.GRADIENT
}

/** @see ribbonDepthLevel */
@Composable
fun rememberRibbonDepthLevel(enabled: Boolean): RibbonDepthLevel =
    remember(enabled) { ribbonDepthLevel(Build.VERSION.SDK_INT, enabled) }

/**
 * The light that runs along a row with the clock.
 *
 * A band of the row's own colour centred on where the progress has reached,
 * falling away on both sides, breathing slowly so the lit row is alive rather
 * than a gradient that happens to be brighter. It is the answer to «что идёт
 * сейчас» drawn rather than written: the one row on the screen with a light
 * moving across it is the one that is happening.
 *
 * The additive highlight is scaled by the pixel's own alpha and clamped to it,
 * which is the fix `LiquidRipple`'s own comment explains: adding a flat colour
 * to a premultiplied pixel makes a transparent one brighter than its alpha
 * allows, and an invalid premultiplied value draws as grey haze over anything
 * see-through. Here that would be a fog over the rounded corners of every lit
 * card.
 */
@Language("AGSL")
private const val RibbonSheenShader = """
    uniform shader inputShader;
    uniform float2 uResolution;
    uniform float uProgress;
    uniform float uTime;
    uniform float3 uTint;
    uniform float uStrength;

    half4 main(float2 fragCoord) {
        half4 src = inputShader.eval(fragCoord);

        // Guarded: a layer measured at zero width is one frame of a row being
        // laid out, and dividing by it puts a NaN into every uniform that
        // follows it.
        float width = max(uResolution.x, 1.0);
        float height = max(uResolution.y, 1.0);
        float2 uv = float2(fragCoord.x / width, fragCoord.y / height);

        // The front, and how far this pixel is behind or ahead of it. The
        // exponential rather than a smoothstep because the band has to have no
        // edge at all: a linear falloff leaves a visible seam travelling across
        // the card, which reads as a rendering fault rather than as light.
        float band = exp(-abs(uv.x - uProgress) * 14.0);

        // Slow, and shifted down the card so the light leans rather than
        // pulsing as one flat sheet.
        float drift = 0.5 + 0.5 * sin(uTime * 1.6 + uv.y * 3.0);

        float light = band * (0.55 + 0.45 * drift) * uStrength;
        half3 add = half3(uTint) * half(light) * src.a;
        return half4(clamp(src.rgb + add, 0.0, src.a), src.a);
    }
"""

/**
 * Lights this row where its own clock has got to.
 *
 * Nothing at all unless [level] is [RibbonDepthLevel.SHADER] and [strength] is
 * above zero, which is what makes the caller's own fade-in free: a row that has
 * just stopped running runs this at a strength approaching zero for a few
 * frames and then stops paying for it entirely.
 *
 * The drift's clock is this modifier's own, and it is read inside the draw
 * lambda rather than in composition. That is the whole difference between an
 * effect that costs a layer redraw per frame and one that recomposes a row of
 * the list sixty times a second: a `MutableState` written by the frame clock
 * and read only where the layer is configured invalidates the draw and nothing
 * above it.
 *
 * @param progress 0..1 along the row, where the light sits.
 * @param tint the row's content colour, which is what keeps a red cancelled row
 *   from being lit in blue.
 * @param strength 0..1; the caller's own animation between "running" and not.
 */
fun Modifier.ribbonSheen(
    level: RibbonDepthLevel,
    progress: Float,
    tint: Color,
    strength: Float,
): Modifier = composed {
    if (!level.drawsShader || strength <= 0f) {
        return@composed Modifier
    }
    RibbonSheenLayer.of(
        progress = progress.coerceIn(0f, 1f),
        tint = tint,
        strength = strength.coerceIn(0f, 1f),
    )
}

/** The layer itself, behind an API guard lint can see. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object RibbonSheenLayer {

    @Composable
    fun of(progress: Float, tint: Color, strength: Float): Modifier {
        // Compiled once, and survivable if it does not compile. A `RuntimeShader`
        // built from source some driver's SkSL rejects throws from its
        // constructor, and a constructor throwing inside a composition takes the
        // app down — for an ornament. Null here is a row that is simply not lit.
        val shader = remember { runCatching { RuntimeShader(RibbonSheenShader) }.getOrNull() }
            ?: return Modifier

        // Wrapped at a hundred seconds so the float stays small: past a few
        // million the spacing between representable values is wider than the
        // step, and the drift visibly stops.
        val time = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                withInfiniteAnimationFrameMillis { millis ->
                    time.floatValue = (millis % TimeWrapMillis) / 1000f
                }
            }
        }

        return Modifier.graphicsLayer {
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uProgress", progress)
            shader.setFloatUniform("uTime", time.floatValue)
            shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uStrength", strength)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "inputShader")
                .asComposeRenderEffect()
        }
    }
}

/** @see RibbonSheenLayer */
private const val TimeWrapMillis = 100_000L

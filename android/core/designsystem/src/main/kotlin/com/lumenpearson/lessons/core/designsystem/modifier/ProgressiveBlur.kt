package com.lumenpearson.lessons.core.designsystem.modifier

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import org.intellij.lang.annotations.Language

/**
 * The progressive-blur shader from `sameerasw/essentials`
 * `ui/modifiers/ProgressiveBlurModifier.kt`, unchanged.
 *
 * A 9×9 jittered kernel whose radius ramps from zero to `blurRadius` over the
 * given height, on a power curve so the transition has no visible seam.
 */
@Language("AGSL")
private const val ProgressiveBlurShader = """
    uniform shader content;
    uniform float blurRadius;
    uniform float topHeight;
    uniform float bottomHeight;
    uniform float contentHeight;

    half4 main(float2 fragCoord) {
        // Both edges in one pass. Essentials' shader takes an isTop flag and
        // does one; stacking two of these modifiers to get both would mean two
        // full-screen offscreen layers and two 81-tap kernels per frame.
        float topProgress = topHeight > 0.0
            ? 1.0 - clamp(fragCoord.y / topHeight, 0.0, 1.0)
            : 0.0;
        float bottomProgress = bottomHeight > 0.0
            ? 1.0 - clamp((contentHeight - fragCoord.y) / bottomHeight, 0.0, 1.0)
            : 0.0;
        float progress = max(topProgress, bottomProgress);

        progress = pow(progress, 1.5);

        float radius = progress * blurRadius;

        if (radius <= 0.0) {
            return content.eval(fragCoord);
        }

        half4 accum = half4(0.0);
        float weightSum = 0.0;

        float dither = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453);
        float2 jitter = float2(dither - 0.5, fract(dither * 1.618) - 0.5);

        const int SAMPLES = 4;
        float offsetScale = radius / float(SAMPLES);

        for (int x = -SAMPLES; x <= SAMPLES; x++) {
            for (int y = -SAMPLES; y <= SAMPLES; y++) {
                float2 offset = (float2(float(x), float(y)) + jitter) * offsetScale;

                float distSq = dot(offset, offset);
                float radiusSq = radius * radius;

                if (distSq <= radiusSq) {
                    float weight = exp(-3.0 * distSq / radiusSq);
                    accum += content.eval(fragCoord + offset) * weight;
                    weightSum += weight;
                }
            }
        }

        return accum / weightSum;
    }
"""

/** Blur strength under the status bar, in pixels. Essentials' own number. */
const val StatusBarBlurRadius: Float = 40f

/** How far past the status bar the blur reaches, as a multiple of its height. */
const val StatusBarBlurExtent: Float = 1.15f

/** Opacity of the tint drawn over the blurred strip. */
private const val OverlayAlpha = 0.65f

/**
 * Fades the content out under the top and bottom edges of the screen — the
 * effect that lets a list scroll all the way behind the status bar without the
 * top row turning into noise behind the clock, and out from under the floating
 * toolbar instead of being sliced off by it.
 *
 * Ported from Essentials, including its two escape hatches: the shader is
 * skipped in battery-saver mode, and on the Samsung builds whose blur
 * implementation makes the whole window flicker. The gradient tint is drawn
 * either way, so the strip still reads as a soft edge on the devices and API
 * levels that get no blur at all.
 *
 * Apply this to the scrolling content only. Applied to a parent that also holds
 * the toolbar, the bottom fade would dissolve the toolbar itself.
 *
 * @param topHeight how tall the top fade is, in pixels; `0f` for none.
 * @param bottomHeight the same for the bottom edge.
 * @param blurRadius pass `0f` to keep the tint but drop the blur.
 */
fun Modifier.progressiveBlur(
    blurRadius: Float,
    topHeight: Float = 0f,
    bottomHeight: Float = 0f,
    showGradientOverlay: Boolean = true,
): Modifier = composed {
    val overlayColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = OverlayAlpha)
    val context = LocalContext.current
    val isPowerSave = remember(context) {
        (context.getSystemService(PowerManager::class.java))?.isPowerSaveMode == true
    }

    val blur = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        blurRadius > 0f &&
        (topHeight > 0f || bottomHeight > 0f) &&
        !isPowerSave &&
        !isBlurProblematicDevice()
    ) {
        ProgressiveBlurLayer.of(blurRadius, topHeight, bottomHeight)
    } else {
        Modifier
    }

    val overlay = if (showGradientOverlay) {
        Modifier.drawWithContent {
            drawContent()
            if (topHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(overlayColor, Color.Transparent),
                        endY = topHeight,
                    ),
                )
            }
            if (bottomHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, overlayColor),
                        startY = size.height - bottomHeight,
                    ),
                )
            }
        }
    } else {
        Modifier
    }

    this.then(blur).then(overlay)
}

/**
 * Samsung's One UI 7 and earlier ship a render-effect implementation that
 * flickers the whole window when a runtime shader is attached to a scrolling
 * layer.
 *
 * Essentials keeps the same check in `DeviceUtils.isBlurProblematicDevice`. It
 * is a vendor check rather than a capability check because there is nothing to
 * query: the API reports success and then draws wrong.
 */
private fun isBlurProblematicDevice(): Boolean {
    if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false
    // One UI 7 shipped on Android 15; anything newer than that is fixed.
    return Build.VERSION.SDK_INT <= Build.VERSION_CODES.VANILLA_ICE_CREAM
}

/**
 * The graphics layer itself, kept behind an API guard lint can see.
 *
 * The shader is built once and remembered. Essentials constructs a fresh
 * `RuntimeShader` *inside* the `graphicsLayer` block, which means the AGSL
 * source is parsed and compiled again on every draw of the layer this modifier
 * is attached to — and in that app, as in this one, that layer is the whole
 * screen. Hoisting it out is the only difference from the original.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object ProgressiveBlurLayer {

    @Composable
    fun of(blurRadius: Float, topHeight: Float, bottomHeight: Float): Modifier {
        val shader = remember { RuntimeShader(ProgressiveBlurShader) }
        return Modifier.graphicsLayer {
            shader.setFloatUniform("blurRadius", blurRadius)
            shader.setFloatUniform("topHeight", topHeight)
            shader.setFloatUniform("bottomHeight", bottomHeight)
            shader.setFloatUniform("contentHeight", size.height)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

package com.lumenpearson.lessons.core.designsystem.modifier

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.intellij.lang.annotations.Language

/**
 * The progressive-blur shader from `sameerasw/essentials`
 * `ui/modifiers/ProgressiveBlurModifier.kt`.
 *
 * A 9×9 jittered kernel whose radius ramps from zero to the edge's radius over
 * the given height, on a power curve so the transition has no visible seam. The
 * kernel, the dither and the curve are the original's; the two differences are
 * that both edges are computed in one pass instead of one per modifier, and that
 * each edge carries its own radius.
 */
@Language("AGSL")
private const val ProgressiveBlurShader = """
    uniform shader content;
    uniform float topRadius;
    uniform float bottomRadius;
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
        // Two radii rather than one. The top edge fades in as the content
        // scrolls under the status bar, so it has its own strength while the
        // bottom edge stays put under the toolbar; a single radius would make
        // the bottom fade come and go with the scroll too.
        float radius = max(
            pow(topProgress, 1.5) * topRadius,
            pow(bottomProgress, 1.5) * bottomRadius
        );

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

/**
 * How far up from the bottom edge the fade reaches.
 *
 * Essentials' own number, from the screen this shell is modelled on:
 * `MainActivity.kt` blurs its pager with `height = 130.dp.toPx()` and
 * `BlurDirection.BOTTOM`, while its settings-shaped screens use 150.dp. It is a
 * fixed distance rather than the toolbar's height, and that is the point — the
 * fade is there to let a list dissolve on its way *towards* the toolbar, so it
 * has to start well above it. Measured against the bar, the list stays sharp
 * until it is already behind the bar and there is nothing left to soften.
 */
val BottomBlurHeight: Dp = 130.dp

/** Opacity of the tint drawn over the blurred strip. */
private const val OverlayAlpha = 0.65f

/**
 * How far a list scrolls before the top fade is fully in, in pixels.
 *
 * The fade is not on from the first frame. At rest the first row sits below the
 * status bar with nothing behind it, and blurring an empty strip only makes the
 * clock sit on a smudge; the effect is there to rescue content that has scrolled
 * *under* the bar, so it arrives as that content does. 72 px is roughly half a
 * row: far enough not to flicker on a one-finger nudge, close enough that the
 * fade is already there by the time anything has reached the bar.
 */
const val TopBlurRampPx: Float = 72f

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
 * @param topFraction how far in the top fade is, `0f`..`1f`. Drive it from the
 *   scroll position — see [TopBlurRampPx] — so the strip arrives with the
 *   content it exists to soften rather than sitting there from the first frame.
 */
fun Modifier.progressiveBlur(
    blurRadius: Float,
    topHeight: Float = 0f,
    bottomHeight: Float = 0f,
    topFraction: Float = 1f,
    showGradientOverlay: Boolean = true,
): Modifier = composed {
    val fraction = topFraction.coerceIn(0f, 1f)
    val baseOverlay = MaterialTheme.colorScheme.surfaceContainer
    val topOverlayColor = baseOverlay.copy(alpha = OverlayAlpha * fraction)
    val bottomOverlayColor = baseOverlay.copy(alpha = OverlayAlpha)
    val isPowerSave = rememberPowerSaveMode()

    val blur = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        blurRadius > 0f &&
        (topHeight > 0f || bottomHeight > 0f) &&
        !isPowerSave &&
        !isBlurProblematicDevice()
    ) {
        ProgressiveBlurLayer.of(
            topRadius = blurRadius * fraction,
            bottomRadius = blurRadius,
            topHeight = topHeight,
            bottomHeight = bottomHeight,
        )
    } else {
        Modifier
    }

    val overlay = if (showGradientOverlay) {
        Modifier.drawWithContent {
            drawContent()
            if (topHeight > 0f && fraction > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(topOverlayColor, Color.Transparent),
                        endY = topHeight,
                    ),
                )
            }
            if (bottomHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, bottomOverlayColor),
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
 * Whether the device is in battery-saver mode, kept up to date while this is in
 * the composition.
 *
 * Read once and remembered, which is what this used to be, the escape hatch only
 * ever worked for somebody who had switched battery saver on *before* the screen
 * was composed — and it is switched on precisely when a phone has been running
 * for a while, which is to say while the app is already open. The platform
 * announces the change; listening costs one registered receiver per blurred
 * surface, and the surface is the shell, not a list row.
 */
@Composable
internal fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var isPowerSave by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }

    DisposableEffect(power) {
        if (power == null) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isPowerSave = power.isPowerSaveMode
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        // Re-read on the way in as well: the mode can have changed between the
        // remember above and the registration, and that gap is a whole frame.
        isPowerSave = power.isPowerSaveMode
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return isPowerSave
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
    fun of(
        topRadius: Float,
        bottomRadius: Float,
        topHeight: Float,
        bottomHeight: Float,
    ): Modifier {
        val shader = remember { RuntimeShader(ProgressiveBlurShader) }
        return Modifier.graphicsLayer {
            shader.setFloatUniform("topRadius", topRadius)
            shader.setFloatUniform("bottomRadius", bottomRadius)
            shader.setFloatUniform("topHeight", topHeight)
            shader.setFloatUniform("bottomHeight", bottomHeight)
            shader.setFloatUniform("contentHeight", size.height)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

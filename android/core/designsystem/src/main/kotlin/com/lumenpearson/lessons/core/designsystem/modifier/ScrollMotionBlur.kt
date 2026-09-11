package com.lumenpearson.lessons.core.designsystem.modifier

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import org.intellij.lang.annotations.Language

/**
 * The AGSL directional blur from `sameerasw/essentials`
 * `ui/modifiers/ScrollMotionBlurModifier.kt`, unchanged.
 *
 * Ten taps along the scroll axis, weighted so the trailing samples fade, which
 * is what makes a fast fling look like motion rather than like a smear.
 */
@Language("AGSL")
private const val DirectionalBlurShader = """
    uniform shader composable;
    uniform float2 resolution;
    uniform float scrollVelocity;
    uniform float isHorizontal;
    uniform float blurScale;

    half4 main(float2 fragCoord) {
        const int SAMPLES = 10;
        half4 color = half4(0.0);
        float totalWeight = 0.0;

        float blurMagnitude = clamp(scrollVelocity * 22.0 * blurScale, -60.0, 60.0);

        for (int i = 0; i < SAMPLES; i++) {
            float offset = (float(i) / float(SAMPLES - 1) - 0.5) * blurMagnitude;

            float2 sampleCoord;
            if (isHorizontal > 0.5) {
                float clampedX = clamp(fragCoord.x + offset, 0.0, resolution.x);
                sampleCoord = float2(clampedX, fragCoord.y);
            } else {
                float clampedY = clamp(fragCoord.y + offset, 0.0, resolution.y);
                sampleCoord = float2(fragCoord.x, clampedY);
            }

            float weight = 1.0 - abs(offset / (abs(blurMagnitude) + 0.001)) * 0.5;

            color += composable.eval(sampleCoord) * weight;
            totalWeight += weight;
        }

        return color / totalWeight;
    }
"""

/** Below this the layer is drawn untouched; a shader for nothing costs a frame. */
private const val VelocityFloor = 0.05f

/** Velocity is clamped here before it reaches the shader. */
private const val VelocityCeiling = 3f

/** One list index is worth this many pixels when estimating a lazy list's speed. */
private const val ItemHeightGuessPx = 80f

/** A page of a pager is worth this many pixels. */
private const val PageWidthGuessPx = 400f

/** Weight of the new sample against the running average. */
private const val NewSampleWeight = 0.65f

/** How much of the previous velocity survives a frame with no movement. */
private const val DecayFactor = 0.45f

/**
 * Blurs a scrolling container along its scroll axis, in proportion to how fast
 * it is actually moving.
 *
 * Ported from Essentials, with one change: the blur amount is a parameter rather
 * than a `SharedPreferences` read on every composition. The reference re-reads
 * the file inside the modifier and registers a listener to catch changes; this
 * app already has the value in a settings flow, and passing it in removes both
 * the disk read and the listener.
 *
 * Requires Android 13 (AGSL runtime shaders); below that, and whenever
 * [enabled] is false, this is `Modifier` and nothing is drawn differently.
 */
fun Modifier.scrollMotionBlur(
    state: LazyListState,
    enabled: Boolean = true,
    scale: Float = 1f,
    isHorizontal: Boolean = false,
): Modifier = composed {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return@composed Modifier
    }
    val velocity = remember { Animatable(0f) }

    LaunchedEffect(state) {
        state.whileScrolling(velocity) {
            var previousIndex = state.firstVisibleItemIndex
            var previousOffset = state.firstVisibleItemScrollOffset
            trackVelocity(velocity) {
                val delta = (state.firstVisibleItemIndex - previousIndex) * ItemHeightGuessPx +
                    (state.firstVisibleItemScrollOffset - previousOffset)
                previousIndex = state.firstVisibleItemIndex
                previousOffset = state.firstVisibleItemScrollOffset
                delta
            }
        }
    }

    MotionBlurLayer.of(velocity, isHorizontal, scale)
}

/** @see scrollMotionBlur */
fun Modifier.scrollMotionBlur(
    state: ScrollState,
    enabled: Boolean = true,
    scale: Float = 1f,
    isHorizontal: Boolean = false,
): Modifier = composed {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return@composed Modifier
    }
    val velocity = remember { Animatable(0f) }

    LaunchedEffect(state) {
        state.whileScrolling(velocity) {
            var previous = state.value
            trackVelocity(velocity) {
                val delta = (state.value - previous).toFloat()
                previous = state.value
                delta
            }
        }
    }

    MotionBlurLayer.of(velocity, isHorizontal, scale)
}

/** @see scrollMotionBlur */
fun Modifier.scrollMotionBlur(
    state: PagerState,
    enabled: Boolean = true,
    scale: Float = 1f,
): Modifier = composed {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return@composed Modifier
    }
    val velocity = remember { Animatable(0f) }

    LaunchedEffect(state) {
        state.whileScrolling(velocity) {
            var previous = state.currentPage + state.currentPageOffsetFraction
            trackVelocity(velocity) {
                val position = state.currentPage + state.currentPageOffsetFraction
                val delta = (position - previous) * PageWidthGuessPx
                previous = position
                delta
            }
        }
    }

    MotionBlurLayer.of(velocity, isHorizontal = true, scale = scale)
}

/** How long the blur takes to fade once the finger is off. */
private const val SettleMillis = 60

/** Frames further apart than this are a dropped frame, not a fast scroll. */
private val PlausibleFrameMillis = 1f..100f

/**
 * Runs [block] for exactly as long as the container is being scrolled, and
 * settles the blur away when it stops.
 *
 * The reference leaves its frame loop running for the lifetime of the modifier,
 * which means it asks for a frame every frame forever — on a static list, on
 * three off-screen pager pages at once, on a phone in a pocket. It also lets
 * that loop's `snapTo` race the settle animation for the same `Animatable`, so
 * the settle never actually runs. Scoping the loop to the gesture fixes both:
 * there is nothing to race, because the loop is gone by the time the settle
 * starts.
 */
private suspend fun ScrollableState.whileScrolling(
    velocity: Animatable<Float, *>,
    block: suspend () -> Unit,
) {
    snapshotFlow { isScrollInProgress }.collectLatest { scrolling ->
        if (scrolling) block() else velocity.animateTo(0f, tween(SettleMillis))
    }
}

/**
 * The per-frame loop that turns "how far did it move" into a smoothed velocity.
 *
 * Written once here instead of three times: Essentials repeats this block for
 * each scrollable type, and the four copies have already drifted — the pager one
 * uses a different smoothing constant from the rest.
 *
 * @param delta called inside the frame callback; returns pixels moved since the
 *   previous frame and is responsible for updating its own previous value.
 */
private suspend fun trackVelocity(
    velocity: Animatable<Float, *>,
    delta: () -> Float,
) {
    var previousFrameNanos = 0L
    while (currentCoroutineContext().isActive) {
        var next = 0f
        withFrameNanos { frameNanos ->
            val moved = delta()
            if (previousFrameNanos != 0L) {
                val elapsedMillis = (frameNanos - previousFrameNanos) / 1_000_000f
                next = when {
                    elapsedMillis !in PlausibleFrameMillis -> 0f

                    abs(moved) > 0.1f -> {
                        val sample = (moved / elapsedMillis).coerceIn(
                            -VelocityCeiling,
                            VelocityCeiling,
                        )
                        velocity.value * (1f - NewSampleWeight) + sample * NewSampleWeight
                    }

                    else -> (velocity.value * DecayFactor).takeIf { abs(it) >= 0.01f } ?: 0f
                }
            }
            previousFrameNanos = frameNanos
        }
        velocity.snapTo(next)
    }
}

/** The graphics layer itself, kept behind an API guard lint can see. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object MotionBlurLayer {

    @Composable
    fun of(velocity: Animatable<Float, *>, isHorizontal: Boolean, scale: Float): Modifier {
        val shader = remember { RuntimeShader(DirectionalBlurShader) }
        return Modifier.graphicsLayer {
            val current = velocity.value
            renderEffect = if (abs(current) > VelocityFloor) {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("scrollVelocity", current)
                shader.setFloatUniform("isHorizontal", if (isHorizontal) 1f else 0f)
                shader.setFloatUniform("blurScale", scale)
                RenderEffect.createRuntimeShaderEffect(shader, "composable").asComposeRenderEffect()
            } else {
                null
            }
        }
    }
}

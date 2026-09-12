package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.modifier.scrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.modifier.slideMotionBlur

/**
 * How much room a scrolling screen must leave at its bottom for the floating
 * toolbar.
 *
 * The toolbar is drawn *over* the content rather than beside it, so no screen
 * can work its height out on its own — and the number is not a constant either:
 * it is the pill plus its margins plus whatever the gesture bar takes, which
 * differs between a phone with buttons and one without. The shell measures the
 * real thing and publishes it here, so a screen writes
 * `bottom = LocalBottomBarSpace.current` and is correct on every device.
 *
 * The default is the height of the bar on a gesture-navigation phone, used for
 * the first frame before the measurement lands and by `@Preview`s, which have
 * no shell above them.
 */
val LocalBottomBarSpace = compositionLocalOf { 96.dp }

/**
 * Whether lists blur along their scroll axis, and by how much.
 *
 * Published by the shell from the stored settings so that a screen can turn the
 * effect on with one modifier and without learning that a preference exists —
 * the same trick Essentials plays by reading its `SharedPreferences` inside the
 * modifier, minus the disk read.
 */
@Immutable
data class ScrollBlurSettings(
    val enabled: Boolean = false,
    val scale: Float = 1f,
)

/** @see ScrollBlurSettings */
val LocalScrollBlur = staticCompositionLocalOf { ScrollBlurSettings() }

/**
 * Whether the app animates at all, and how fast.
 *
 * Published by the shell from the stored settings, next to [LocalScrollBlur] and
 * for the same reason: the thing that has to act on the preference is a
 * transition spec three layers below the screen that reads it.
 *
 * @property enabled off means *instant*, not fast. A transition run at ten times
 *   the speed is still a transition, and the people who turn animations off are
 *   the people for whom the movement itself is the problem — motion sickness, a
 *   vestibular disorder, or a phone slow enough that every animation is a stutter.
 * @property speed multiplies the pace: 2 is twice as quick, 0.5 half. Scaled
 *   into durations by [durationMillis] rather than by each caller, so the
 *   direction of the multiplication is decided once.
 */
@Immutable
data class MotionSettings(
    val enabled: Boolean = true,
    val speed: Float = 1f,
) {
    /**
     * [base] milliseconds at the chosen speed, or 0 when animations are off.
     *
     * [speed] is clamped away from zero before dividing: it arrives from a
     * preferences file, and a stored 0 would otherwise produce an infinite
     * duration — an animation that never finishes and a screen that never
     * arrives.
     */
    fun durationMillis(base: Int): Int =
        if (!enabled) 0 else (base / speed.coerceIn(MinMotionSpeed, MaxMotionSpeed)).toInt()

    /**
     * A spring's stiffness at the chosen speed.
     *
     * Multiplied, not divided: stiffness is the inverse of duration, so a faster
     * setting means a stiffer spring. Damping is deliberately left alone — it is
     * what decides whether the page overshoots, which is a matter of character
     * rather than of pace.
     */
    fun stiffness(base: Float): Float = base * speed.coerceIn(MinMotionSpeed, MaxMotionSpeed)
}

/** Ends of the motion-speed range; see [MotionSettings.speed]. */
const val MinMotionSpeed: Float = 0.5f

/** @see MinMotionSpeed */
const val MaxMotionSpeed: Float = 2f

/** @see MotionSettings */
val LocalMotion = staticCompositionLocalOf { MotionSettings() }

/** Motion blur for a lazy list, wired to the user's setting. */
fun Modifier.appScrollMotionBlur(state: LazyListState): Modifier = composed {
    val settings = LocalScrollBlur.current
    scrollMotionBlur(state = state, enabled = settings.enabled, scale = settings.scale)
}

/** Motion blur for a plain scrolling column, wired to the user's setting. */
fun Modifier.appScrollMotionBlur(state: ScrollState): Modifier = composed {
    val settings = LocalScrollBlur.current
    scrollMotionBlur(state = state, enabled = settings.enabled, scale = settings.scale)
}

/**
 * Motion blur for the swipe between tabs, wired to the user's setting.
 *
 * The setting is called "размытие при прокрутке" and a tab swipe is the largest
 * scroll in the app, so leaving the pager out of it made the option look broken
 * on the one gesture people use most.
 */
fun Modifier.appScrollMotionBlur(state: PagerState): Modifier = composed {
    val settings = LocalScrollBlur.current
    scrollMotionBlur(state = state, enabled = settings.enabled, scale = settings.scale)
}

/**
 * Motion blur for a screen sliding in over another, wired to the user's setting.
 *
 * @see slideMotionBlur
 */
fun Modifier.appSlideMotionBlur(
    moving: () -> Boolean,
    fraction: () -> Float,
    travel: Dp,
): Modifier = composed {
    val settings = LocalScrollBlur.current
    slideMotionBlur(
        moving = moving,
        fraction = fraction,
        travel = travel,
        enabled = settings.enabled,
        scale = settings.scale,
    )
}

/** Extra breathing room between the last row of a screen and the toolbar. */
val BottomBarGap: Dp = 8.dp

/**
 * Room a screen leaves above its first row, now that there is no top app bar.
 *
 * Content is drawn from the very top of the window so that it can pass *under*
 * the status bar and be softened there, which is the whole point of the top
 * fade. That means the inset is the screen's own business: it belongs in the
 * list's `contentPadding`, not in a `padding` that would stop the list short of
 * the bar it is supposed to scroll behind.
 */
@Composable
fun statusBarSpace(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * How far the screen under the toolbar has scrolled, in pixels.
 *
 * The shell draws the top fade, but only the screen knows whether anything has
 * moved yet, and the shell holds four of them at once — the three pages of the
 * pager plus whatever settings page is open. So the shell hands each screen its
 * own holder and reads back the one belonging to the screen in front, rather
 * than every screen writing into a single value and the off-screen ones winning.
 */
@Stable
class ScrollOffsetHolder {
    var value: Float by mutableFloatStateOf(0f)
        internal set

    internal fun report(offset: Float) {
        value = offset
    }
}

/** @see ScrollOffsetHolder */
val LocalScrollOffset = compositionLocalOf { ScrollOffsetHolder() }

/**
 * Publishes [state]'s scroll position to the shell, for the top fade.
 *
 * Only the distance from the top matters and only up to about one row of it, so
 * a list scrolled past its first item reports a number large enough to mean
 * "fully in" rather than its true offset, which nothing reads and which would
 * cost a measurement of every item above.
 */
@Composable
fun ReportScrollOffset(state: LazyListState) {
    val holder = LocalScrollOffset.current
    LaunchedEffect(state, holder) {
        snapshotFlow {
            if (state.firstVisibleItemIndex > 0) {
                Float.MAX_VALUE
            } else {
                state.firstVisibleItemScrollOffset.toFloat()
            }
        }.collect(holder::report)
    }
}

/** @see ReportScrollOffset */
@Composable
fun ReportScrollOffset(state: ScrollState) {
    val holder = LocalScrollOffset.current
    LaunchedEffect(state, holder) {
        snapshotFlow { state.value.toFloat() }.collect(holder::report)
    }
}

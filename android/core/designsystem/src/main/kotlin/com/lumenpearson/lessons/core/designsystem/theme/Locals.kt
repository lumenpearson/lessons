package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.modifier.scrollMotionBlur

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

/** Extra breathing room between the last row of a screen and the toolbar. */
val BottomBarGap: Dp = 8.dp

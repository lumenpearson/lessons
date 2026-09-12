package com.lumenpearson.lessons.core.designsystem.modifier

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified

/**
 * One ripple for the whole window, fired from wherever the reason for it is.
 *
 * The wave is drawn by the shell, on the layer that holds every screen and the
 * toolbar, because a wave that stopped at a page edge would give away that the
 * page has an edge. But the things that deserve a wave — the debug switch, a
 * theme change, the answer to an update — live three screens down from the
 * shell. This is the handle they reach for: the shell owns one, publishes it
 * through [LocalLiquidRipple], and the control that was tapped calls [fire] with
 * its own centre.
 *
 * Counted rather than flagged, for the same reason as the modifier underneath:
 * the thing being answered is a tap, and two taps are two waves.
 */
@Stable
class LiquidRippleState {

    /** Bumped on every [fire]; the modifier keys its animation on it. */
    var trigger: Int by mutableIntStateOf(0)
        private set

    /** Where the last wave started, in root coordinates. */
    var origin: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /** Whether the last wave runs inward. */
    var reverse: Boolean by mutableStateOf(false)
        private set

    /**
     * Starts a wave.
     *
     * @param origin the control's centre in root coordinates —
     *   `positionInRoot()` plus half its size — or [Offset.Unspecified] for the
     *   middle of the screen.
     * @param reverse play it backwards: the ring closes on the origin instead of
     *   leaving it. For a "no", said with the same gesture as the "yes".
     */
    fun fire(origin: Offset, reverse: Boolean = false) {
        this.origin = origin
        this.reverse = reverse
        trigger++
    }
}

/** The shell's ripple, or `null` outside the shell (previews, the widget host). */
val LocalLiquidRipple = staticCompositionLocalOf<LiquidRippleState?> { null }

/**
 * Draws [state]'s waves over whatever this is applied to. Put it on the layer
 * that covers the window, once.
 *
 * @param enabled the user's setting; off, nothing is drawn and [state] is still
 *   safe to fire into, so no caller has to know the setting exists.
 */
fun Modifier.liquidRipple(state: LiquidRippleState, enabled: Boolean = true): Modifier =
    liquidRipple(
        trigger = state.trigger,
        origin = state.origin,
        enabled = enabled,
        reverse = state.reverse,
    )

/**
 * Fires a wave from whatever control inside was pressed, and nothing else.
 *
 * [com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealAnchor] does this
 * too, but it also wipes the screen to a new theme, which is exactly right for a
 * row that repaints the app and exactly wrong for one that does not. The switch
 * that turns the ripple *on* is the clearest case: the honest way to show what
 * the setting does is to do it, once, starting at the switch that was just
 * flipped — and a theme wipe riding along would be a second answer to a question
 * nobody asked.
 *
 * The origin comes from [ControlCentre], so it is the switch or the segment
 * rather than the middle of the row that holds it; the box's own centre is the
 * fallback for a control that does not report one.
 */
@Composable
fun LiquidRippleAnchor(
    modifier: Modifier = Modifier,
    content: @Composable (fire: () -> Unit) -> Unit,
) {
    val ripple = LocalLiquidRipple.current
    var centre by remember { mutableStateOf(Offset.Unspecified) }
    val control = remember { ControlCentre() }

    Box(modifier = modifier.centreInRoot { centre = it }) {
        CompositionLocalProvider(LocalControlCentre provides control) {
            content {
                val from = if (control.offset.isSpecified) control.offset else centre
                ripple?.fire(from)
            }
        }
    }
}

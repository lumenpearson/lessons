package com.lumenpearson.lessons.core.designsystem.modifier

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion

/**
 * The restless wobble an iOS home screen gives an icon you are allowed to move.
 *
 * It is not decoration. A long press that changed nothing visible would leave
 * the reader holding a mode they cannot see they are in — and the only other
 * way to say «these can be moved now» is a word, which costs a string, a
 * translation and a row of the bar that has none to spare. The wobble says it
 * without any of that, and says it about *each* item rather than about the bar,
 * which is the distinction that matters when only some things are draggable.
 *
 * ### What makes it read as iOS rather than as a broken animation
 *
 * Two things, and both are the reason this is a component rather than four
 * lines at the call site.
 *
 * **It rotates about the centre and translates a little.** Rotation alone reads
 * as a rocking sign; translation alone as a shiver. Together they read as an
 * object held loosely.
 *
 * **Every item is out of phase with its neighbours.** A row rotating in lockstep
 * reads as one object flexing — the bar itself wobbling — rather than as several
 * objects each loose in its own right. [phase] shifts the start and stretches
 * the period a few per cent per item, which is enough for the eye to stop
 * finding the pattern and is why the period is not a constant.
 *
 * ### Two things it must not do
 *
 * It must not run when it is not wanted: **an infinite animation means Compose's
 * clock is never idle**, so every test that composes this subtree would have
 * `waitForIdle` hang rather than fail — the trap `MarqueeText` already pays for
 * and `MarqueeClockTest` already guards. So when [active] is false this returns
 * the receiver untouched and starts no transition at all, rather than starting
 * one at zero amplitude.
 *
 * And it must not run when the reader has turned animation off. Somebody who
 * has done that has said something about their phone, not about this bar, and a
 * perpetual wobble is the least ignorable animation in the app. With motion off
 * the item sits still and the mode is carried by the haptic, the dimmed
 * selection and the drag itself.
 *
 * @param active whether the item is in a mode where it can be moved.
 * @param phase the item's position in its row, so neighbours differ.
 */
fun Modifier.jiggling(active: Boolean, phase: Int): Modifier = composed {
    val motion = LocalMotion.current
    if (!active || !motion.enabled) return@composed this

    val transition = rememberInfiniteTransition(label = "jiggle")
    // Odd numbers of milliseconds, and a different one per item: two periods
    // that divide into each other re-synchronise every few seconds, which is
    // visible as the row briefly moving as one.
    val period = motion.durationMillis(JigglePeriodMillis + phase * JigglePeriodSpreadMillis)
        .coerceAtLeast(MinJigglePeriodMillis)
    val offset = StartOffset((phase * JigglePhaseMillis) % period)

    val swing by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Linear on purpose. An eased swing pauses at each end, which reads
            // as something being shaken deliberately; the loose-object look
            // comes from a constant angular speed reversing hard.
            animation = tween(durationMillis = period, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = offset,
        ),
        label = "jiggle_swing",
    )

    graphicsLayer {
        rotationZ = swing * JiggleDegrees
        // Across the row rather than along it: a horizontal wobble competes with
        // the drag the mode exists for, and with the neighbours sliding sideways
        // to open a slot.
        translationY = swing * JiggleLiftPx * density
    }
}

/**
 * How far it turns, in degrees either side of upright.
 *
 * iOS is about two. More than three stops reading as «loose» and starts reading
 * as «wrong», and on a 24 dp icon three degrees is already only about a pixel of
 * travel at the corners — the effect is carried by the *motion*, not the angle.
 */
private const val JiggleDegrees = 2.2f

/** And how far it lifts, in density-independent pixels either side of centre. */
private const val JiggleLiftPx = 0.9f

/** One swing, before the reader's motion-speed setting is applied. */
private const val JigglePeriodMillis = 130

/** Added per item, so two neighbours never share a period. */
private const val JigglePeriodSpreadMillis = 11

/** And how far into its own swing each item starts. */
private const val JigglePhaseMillis = 47

/**
 * A floor under the period, because the speed setting divides.
 *
 * At the fastest setting the computed period falls under a frame, and an
 * animation that reverses more than once between two frames is not a wobble —
 * it is aliasing, and what it actually looks like is an icon that has gone
 * blurry and still.
 */
private const val MinJigglePeriodMillis = 60

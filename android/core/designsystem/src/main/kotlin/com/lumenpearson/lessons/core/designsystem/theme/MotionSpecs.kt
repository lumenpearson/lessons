package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.spring as composeSpring
import androidx.compose.animation.core.tween as composeTween

/**
 * The app's animation specs, read from the reader's motion settings (#247).
 *
 * Every animation the app draws goes through one of these, so that «Анимации»
 * and the speed in «Взаимодействие» reach all of them rather than the ones that
 * happened to read [LocalMotion]. Before this, the bottom bar, the calendar, the
 * bell and the loading shimmer animated with the switch off (#246).
 *
 * Off is *instant* — a snap, or no transition at all — never merely fast, for
 * the reason [MotionSettings.enabled] gives.
 */

/** How long something takes to fade in or out at the normal speed. */
const val FadeMillis: Int = 220

/** How long a change of content cross-fades at the normal speed. */
const val CrossfadeMillis: Int = 260

/** A spring at the reader's pace, or a snap when animations are off. */
fun <T> MotionSettings.springSpec(
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMediumLow,
    visibilityThreshold: T? = null,
): FiniteAnimationSpec<T> =
    if (!enabled) snap() else composeSpring(dampingRatio, stiffness(stiffness), visibilityThreshold)

/** A tween of [millis] at the reader's pace, or a snap when animations are off. */
fun <T> MotionSettings.tweenSpec(
    millis: Int,
    easing: Easing = FastOutSlowInEasing,
    delayMillis: Int = 0,
): FiniteAnimationSpec<T> =
    if (!enabled) snap() else composeTween(durationMillis(millis), durationMillis(delayMillis), easing)

/** How an element comes into view: it fades in as the room for it opens. */
fun MotionSettings.appear(): EnterTransition =
    if (!enabled) {
        EnterTransition.None
    } else {
        fadeIn(tweenSpec(FadeMillis)) +
            expandVertically(springSpec(visibilityThreshold = IntSize.VisibilityThreshold))
    }

/** How an element leaves: it fades out as the room it took closes. */
fun MotionSettings.disappear(): ExitTransition =
    if (!enabled) {
        ExitTransition.None
    } else {
        fadeOut(tweenSpec(FadeMillis)) +
            shrinkVertically(springSpec(visibilityThreshold = IntSize.VisibilityThreshold))
    }

/**
 * A part of a group that is there only while [visible]: a setting under the
 * switch it belongs to, a detail under its summary.
 *
 * It used to be an `if`, so turning a switch on made the rows under it appear
 * between two frames and pushed everything below them down at once.
 */
@Composable
fun ColumnScope.Reveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = motion.appear(),
        exit = motion.disappear(),
    ) {
        // A column of its own, spaced like the group it sits in, because what
        // is revealed is often two rows and an animated box would stack them.
        Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
            content()
        }
    }
}

/**
 * [content] for [targetState], cross-fading into the next one: a placeholder
 * into the rows it stood for, one empty state into another.
 */
@Composable
fun <T> MotionCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "crossfade",
    content: @Composable (T) -> Unit,
) {
    val motion = LocalMotion.current
    Crossfade(
        targetState = targetState,
        modifier = modifier,
        animationSpec = motion.tweenSpec(CrossfadeMillis),
        label = label,
        content = content,
    )
}

/**
 * The modifier that lets a list item fade in, fade out and slide to its new
 * place when the items around it change — or nothing, when animations are off.
 * It belongs on the item's root, which is why [animatedItem] puts it there.
 */
@Composable
fun LazyItemScope.motionItemModifier(): Modifier {
    val motion = LocalMotion.current
    if (!motion.enabled) return Modifier
    return Modifier.animateItem(
        fadeInSpec = motion.tweenSpec(FadeMillis),
        placementSpec = motion.springSpec(visibilityThreshold = IntOffset.VisibilityThreshold),
        fadeOutSpec = motion.tweenSpec(FadeMillis),
    )
}

/**
 * [LazyListScope.item], but one that arrives, leaves and moves under the motion
 * settings rather than appearing and vanishing between two frames (#247).
 */
fun LazyListScope.animatedItem(
    key: Any,
    contentType: Any? = null,
    content: @Composable LazyItemScope.() -> Unit,
) {
    item(key = key, contentType = contentType) {
        Box(modifier = motionItemModifier()) {
            this@item.content()
        }
    }
}

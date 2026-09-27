package com.lumenpearson.lessons.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.unit.IntOffset
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings

/** How long a settings page takes to slide in over the tabs. */
private const val PageTransitionMillis = 320

/**
 * Forward pushes the old page off to the left; back slides it away to the right.
 *
 * One spec for the movement and the fade, rather than a tween on one and a
 * spring on the other: given different curves the arriving page reaches full
 * opacity while it is still visibly moving, and the leaving one disappears
 * before it is off the screen.
 *
 * `SizeTransform(clip = false)` because settings pages are wildly different
 * heights, and the default animates the slot's size *and* clips to it — which
 * crops whichever page is taller for the length of the slide.
 */
internal fun pageTransition(forward: Boolean, motion: MotionSettings): ContentTransform {
    // Instant, not quick. Scaling the durations towards zero would still slide
    // the page — a two-frame slide is a flicker, which is worse than no
    // animation for exactly the people who switch animations off.
    if (!motion.enabled) {
        return ContentTransform(
            targetContentEnter = EnterTransition.None,
            initialContentExit = ExitTransition.None,
            sizeTransform = SizeTransform(clip = false),
        )
    }

    val fade = tween<Float>(motion.durationMillis(PageTransitionMillis))
    val enter = slideInHorizontally(animationSpec = pageSlideSpring(motion)) { width ->
        if (forward) width else -width
    } + fadeIn(animationSpec = fade)

    val exit = slideOutHorizontally(animationSpec = pageSlideSpring(motion)) { width ->
        if (forward) -width else width
    } + fadeOut(animationSpec = fade)

    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = SizeTransform(clip = false),
    )
}

/** A stiffer spring is a faster one; see [MotionSettings.stiffness]. */
private fun pageSlideSpring(motion: MotionSettings) = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = motion.stiffness(Spring.StiffnessMediumLow),
)

/**
 * Moves the pager to [page] — instantly when the user has switched animations
 * off, and with the usual glide otherwise.
 *
 * A tab tap is the movement people make most, so leaving it animated while the
 * settings pages had become instant would have made the switch look broken from
 * the very screen it lives on.
 */
internal suspend fun PagerState.goToPage(motion: MotionSettings, page: Int) {
    if (motion.enabled) animateScrollToPage(page) else scrollToPage(page)
}

/**
 * A settings page slides in across a third of the screen, not the whole of it.
 *
 * Named because the blur needs the same number: the shader is fed how far the
 * layer really travels, and a second copy of "3" would silently stop matching
 * the first the day the animation is retuned.
 */
private const val LayerTravelDivisor = 3

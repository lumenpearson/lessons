package com.lumenpearson.lessons.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.lumenpearson.lessons.core.designsystem.component.ArrangingDismissLayer
import com.lumenpearson.lessons.core.designsystem.modifier.BottomBlurHeight
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurExtent
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurRadius
import com.lumenpearson.lessons.core.designsystem.modifier.TopBlurRampPx
import com.lumenpearson.lessons.core.designsystem.modifier.progressiveBlur
import com.lumenpearson.lessons.core.designsystem.theme.BottomBarGap
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ScrollOffsetHolder

/**
 * One page of the shell, under the shell's one bar.
 *
 * The bar is drawn by the shell, once, above every page, and morphs into each
 * page's form as the pages slide beneath it (#264). It has been both of the
 * other things. Drawn once and standing still, it showed the arriving page's
 * title the moment one navigated, while the page itself was still sliding in.
 * Drawn per page and travelling with it, it was replaced at every change — one
 * bar leaving with its page while another arrived with the next — which is the
 * thing the owner asked to have transform instead. The morph is what reconciles
 * the two: the bar stays, and its change runs alongside the slide.
 *
 * @param barHeight the bar's height, measured where it is drawn; the page pads
 *   its content and its bottom fade by it.
 * @param onTouchOutsideBar while the tabs are being arranged, what a touch
 *   anywhere but the bar does; see [ArrangingDismissLayer]. Null the rest of the
 *   time, when there is no layer and the page takes its own touches.
 */
@Composable
internal fun ShellScaffold(
    edgeBlur: Boolean,
    statusBarHeightPx: Float,
    offset: ScrollOffsetHolder,
    barHeight: Dp,
    onTouchOutsideBar: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val barHeightPx = with(density) { barHeight.toPx() }

    // Essentials' distance, not the toolbar's height. The bar measures around
    // 60 dp here, so a fade tied to it only began where the toolbar already
    // covered the list — the rows arrived sharp and were cut off rather than
    // dissolving into it. `coerceAtLeast` is the one thing not copied: it keeps
    // the guarantee the measured height gave, that the fade is never shorter
    // than the bar it has to reach behind, on a device whose gesture inset
    // makes the toolbar taller than Essentials' 130 dp.
    val bottomBlurPx = with(density) { BottomBlurHeight.toPx() }.coerceAtLeast(barHeightPx)

    // This page's own scroll drives this page's own fade. The shell used to pick
    // whichever screen was in front and hand one number to everybody, which the
    // page sliding away then wore for the length of the slide.
    // derivedStateOf, because the raw offset changes every scrolled pixel and
    // the fraction it produces does not: past the ramp it is 1f and stays
    // there. Read directly, this composable — the whole page, toolbar included
    // — was invalidated on every frame of every scroll for a number that had
    // stopped moving. The derived read only invalidates when the clamped value
    // actually changes, which is a handful of frames per swipe instead of all
    // of them.
    val target by remember(offset) {
        derivedStateOf { (offset.value / TopBlurRampPx).coerceIn(0f, 1f) }
    }
    val topFraction by animateFloatAsState(targetValue = target, label = "top_blur_fraction")

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomBarSpace provides barHeight + BottomBarGap) {
            // The blur goes on the content, never on the parent that also holds
            // the toolbar: a bottom fade applied there would dissolve the
            // toolbar along with the list running underneath it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .progressiveBlur(
                        blurRadius = if (edgeBlur) StatusBarBlurRadius else 0f,
                        topHeight = statusBarHeightPx * StatusBarBlurExtent,
                        bottomHeight = bottomBlurPx,
                        topFraction = topFraction,
                        showGradientOverlay = edgeBlur,
                    ),
            ) {
                content()
            }
        }

        // Over the page and under the shell's bar, which is drawn above every
        // page, so it takes every touch but the bar's.
        if (onTouchOutsideBar != null) ArrangingDismissLayer(onDismiss = onTouchOutsideBar)
    }
}

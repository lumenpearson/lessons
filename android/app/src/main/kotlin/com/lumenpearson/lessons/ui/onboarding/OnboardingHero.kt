package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import kotlinx.coroutines.delay

/** How tall the strip is on the steps that show it. */
private val HeroHeight: Dp = 148.dp

/** The badge inside the strip, and the glyph inside the badge. */
private val BadgeSize: Dp = 108.dp
private val GlyphSize: Dp = 44.dp

/** A step's dot at rest, and the one for the step being shown. */
private val DotSize: Dp = 8.dp
private val CurrentDotWidth: Dp = 28.dp

/** How long the badge takes to become the next step's shape. */
private const val MorphMillis = 420

/** …and how long the glyph inside it takes to be exchanged. */
private const val GlyphInMillis = 240
private const val GlyphOutMillis = 120

/**
 * The strip above the first-run steps: one shape that becomes the next step's
 * shape, and a row of dots that says how far along the flow is.
 *
 * Adapted from `OnboardingHero.kt` and `OnboardingProgressHeader.kt` in
 * [GMS Flags Reborn](https://github.com/polodarb/GMS-Flags-Reborn) (Apache 2.0)
 * — the licence travels with the app, see the «Лицензии» sheet. What is taken
 * is the idea and its mechanism: a `Morph` between two `MaterialShapes`
 * polygons, drawn on a Canvas, with the progress driven by an `Animatable` that
 * is reset whenever the step changes. What is left behind is the rest of that
 * screen — the orbiting accent shapes, the parallax and the depth maths — which
 * belongs to an app whose first run is four screens of its own artwork. This
 * one is a school timetable; one shape is the whole decoration it wants.
 *
 * **It lives outside the step's own content, above the transition.** That is
 * the entire reason the morph exists: `AnimatedContent` builds a fresh
 * composable per step, so a badge drawn inside a step could only ever fade in
 * as a new object. Held here, across the swap, it has a previous shape to
 * become — which is the difference between the flow reading as five screens and
 * reading as one screen changing its mind.
 *
 * The strip carries the status-bar inset, because it is now the top of the
 * page, and carries it *inside* its animated height so that nothing of it is
 * left behind on the last step. The body under it keeps its own scroll, so what
 * passes under the clock on these four steps is this strip rather than the first
 * card — a change from what `StepScaffold` used to do alone, and the price of a
 * header that survives the step change.
 *
 * On [OnboardingStep.JOIN] the height animates to zero rather than the strip
 * being dropped outright: the join screen is a screen of its own with its own
 * heading, and a shape above it would be a second title. Animating rather than
 * removing keeps the last step's arrival continuous with the four before it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OnboardingHero(
    step: OnboardingStep,
    modifier: Modifier = Modifier,
) {
    val shown = step != OnboardingStep.JOIN
    // The status-bar inset is inside the animated height rather than around it.
    // Held outside, it would survive the collapse and leave the join screen
    // pushed down by a strip that is no longer there — and that screen draws its
    // own content under the bar, as every other full screen in this app does.
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height by animateDpAsState(
        targetValue = if (shown) inset + HeroHeight + DotSize + ScreenPadding else 0.dp,
        animationSpec = tween(MorphMillis),
        label = "onboarding_hero_height",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // The column inside keeps its full height while the box shrinks, so
            // without this the badge would spill over the step sliding in under
            // it for the length of the collapse.
            .clipToBounds(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(inset))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HeroHeight),
                contentAlignment = Alignment.Center,
            ) {
                StepBadge(step = step)
            }
            StepDots(step = step)
            Spacer(Modifier.height(ScreenPadding))
        }
    }
}

/**
 * The badge: the morphing shape, with the step's glyph held in the middle of it.
 *
 * The glyph is exchanged by an `AnimatedContent` rather than morphed — a glyph
 * is a picture of a thing, and half of one picture blended into half of another
 * is not a picture of anything. It scales up out of nothing while the outgoing
 * one scales past the edge, which is the reference's own pairing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepBadge(step: OnboardingStep) {
    // Two pieces of state rather than one: the shape being left and the shape
    // being arrived at. Holding only the target would restart the morph from
    // the *new* shape on a recomposition, which looks like a stutter.
    var from by remember { mutableStateOf(badgeShape(step)) }
    var to by remember { mutableStateOf(badgeShape(step)) }
    var rendered by remember { mutableStateOf(step) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(step) {
        if (step != rendered) {
            from = badgeShape(rendered)
            to = badgeShape(step)
            rendered = step
            progress.snapTo(0f)
            progress.animateTo(targetValue = 1f, animationSpec = tween(MorphMillis))
        }
    }

    val morph = remember(from, to) { Morph(from, to) }
    val path = remember { Path() }
    val container = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = Modifier
            .size(BadgeSize)
            // The shape is decoration. The step is already announced by the
            // title under it, and a screen reader naming a polygon as well
            // would say the same thing twice, less clearly.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(BadgeSize)) {
            drawBadge(
                path = morph.toPath(progress.value, path),
                diameter = size.minDimension,
                // A few degrees of turn while it morphs, so the change reads as
                // one movement rather than as an outline being redrawn.
                rotation = -6f + 8f * progress.value,
                color = container,
            )
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(tween(GlyphInMillis)) + scaleIn(initialScale = 0.7f))
                    .togetherWith(fadeOut(tween(GlyphOutMillis)) + scaleOut(targetScale = 1.2f))
            },
            label = "onboarding_hero_glyph",
        ) { current ->
            Icon(
                imageVector = badgeGlyph(current),
                contentDescription = null,
                modifier = Modifier.size(GlyphSize),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * How far along the flow is: one dot per introduction step, the current one
 * stretched into a bar.
 *
 * [OnboardingStep.JOIN] is deliberately not counted. The dots are a promise
 * about how much reading is left, and the join screen is not reading — it is
 * the thing the reading was for. Counting it would also mean the row losing a
 * dot at the moment the strip collapses, which reads as the flow having got
 * longer.
 */
@Composable
private fun StepDots(step: OnboardingStep) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics {},
    ) {
        OnboardingStep.entries
            .filter { it != OnboardingStep.JOIN }
            .forEach { entry ->
                val current = entry == step
                val width by animateDpAsState(
                    targetValue = if (current) CurrentDotWidth else DotSize,
                    animationSpec = tween(MorphMillis),
                    label = "onboarding_dot_width",
                )
                val colour by animateColorAsState(
                    targetValue = if (entry.ordinal <= step.ordinal) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = tween(MorphMillis),
                    label = "onboarding_dot_colour",
                )
                Surface(
                    modifier = Modifier.size(width = width, height = DotSize),
                    shape = RoundedCornerShape(percent = 50),
                    color = colour,
                    content = {},
                )
            }
    }
}

/**
 * The shape each step wears.
 *
 * Chosen for how different the outlines are from their neighbours rather than
 * for meaning: a morph between two shapes of the same family is a wobble, and
 * the point of the badge is that the change is unmistakable. [OnboardingStep.JOIN]
 * never draws — the strip has collapsed by then — but it needs an entry, and
 * the welcome shape is the honest one to give it: it is the shape the badge is
 * wearing as it leaves.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun badgeShape(step: OnboardingStep): RoundedPolygon = when (step) {
    OnboardingStep.WELCOME -> MaterialShapes.Cookie9Sided
    OnboardingStep.ACKNOWLEDGEMENT -> MaterialShapes.Clover4Leaf
    OnboardingStep.PREFERENCES -> MaterialShapes.Cookie6Sided
    OnboardingStep.PERMISSIONS -> MaterialShapes.PuffyDiamond
    OnboardingStep.JOIN -> MaterialShapes.Cookie9Sided
}

/** The glyph inside the badge: what the step is about, in one picture. */
private fun badgeGlyph(step: OnboardingStep): ImageVector = when (step) {
    OnboardingStep.WELCOME -> Icons.Rounded.WavingHand
    OnboardingStep.ACKNOWLEDGEMENT -> Icons.Rounded.Shield
    OnboardingStep.PREFERENCES -> Icons.Rounded.Tune
    OnboardingStep.PERMISSIONS -> Icons.Rounded.Notifications
    OnboardingStep.JOIN -> Icons.Rounded.Key
}

/**
 * Draws one shape at a given size.
 *
 * A `RoundedPolygon`'s path is unit-sized and centred on the origin, so it has
 * to be scaled and moved into the box before it is any use; doing that with
 * `withTransform` rather than by rebuilding the path keeps one allocation for
 * the whole animation instead of one per frame.
 */
private fun DrawScope.drawBadge(
    path: Path,
    diameter: Float,
    rotation: Float,
    color: Color,
) {
    if (diameter <= 0f) return
    withTransform({
        translate(
            left = (size.width - diameter) / 2f,
            top = (size.height - diameter) / 2f,
        )
        rotate(degrees = rotation, pivot = Offset(diameter / 2f, diameter / 2f))
        scale(scaleX = diameter, scaleY = diameter, pivot = Offset.Zero)
    }) {
        drawPath(path = path, color = color)
    }
}

/**
 * A child that fades and rises into place, so a step's contents arrive one
 * after another rather than all at once.
 *
 * Ported from `OnboardingReveal.kt` in GMS Flags Reborn. It replays whenever it
 * enters the composition, which is exactly once per step swap here, so giving
 * the title one delay and the body a longer one builds the cascade without any
 * of them knowing about the others.
 *
 * The wait is a `delay` before the animation rather than the spec's own
 * `delayMillis`, which is what the reference does and what keeps one rule here:
 * the child is at its start value for exactly as long as it is waiting, and the
 * animation's duration means the same thing for every child in the cascade.
 */
@Composable
internal fun OnboardingReveal(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        progress.animateTo(targetValue = 1f, animationSpec = tween(MorphMillis))
    }

    Box(
        modifier = modifier.graphicsLayer {
            val value = progress.value
            alpha = value
            translationY = (1f - value) * 20.dp.toPx()
        },
    ) {
        content()
    }
}

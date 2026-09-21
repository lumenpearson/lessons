package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.modifier.fadingEdges

/** How far the fade reaches in from each end of a line that is scrolling. */
val MarqueeFadeWidth: Dp = 8.dp

/**
 * One line of text that scrolls when it does not fit, instead of ending in «…».
 *
 * An ellipsis is a promise that there is more and a refusal to show it. On a
 * segmented picker's label that was never acceptable — the whole word is the
 * button — and it is no more acceptable on a subject nobody can read the end
 * of, or on «По этому предмету ничего не задано» cut to «По этому предмету
 * ничего н…» in a sheet with a screenful of room below it. The behaviour was
 * written once for [SegmentedPicker]'s labels and is here so that the rest of
 * the app has the same one rather than a second version of it.
 *
 * **Only a line that actually overflows is touched.** Fading a label that fits
 * would dim its first and last letter for no reason, and a marquee with nothing
 * to scroll is a chance to animate at the wrong moment. Whether it overflows
 * cannot be read off the layout, and that is the trap this component exists to
 * hide: `basicMarquee` hands the text unbounded width, so the text node never
 * reports visual overflow and anything asking `onTextLayout` gets `false`
 * forever. The string is measured against the width the line actually got
 * instead.
 *
 * The fade is applied *outside* the marquee, so it masks the window the text
 * scrolls through rather than travelling with the text.
 *
 * **It scrolls for as long as it is on screen, which is not the default.**
 * `basicMarquee` stops after `MarqueeDefaults.Iterations` — three — and parks
 * the line back at its start, clipped. The budget is spent per composition, so
 * on one list the row that has just been scrolled into view moves and the row
 * that has been sitting there since the screen opened does not: two lines of
 * the same component behaving differently, a few seconds apart, with nothing to
 * see in the code. That was reported from a real phone as «на некоторых лейблах
 * не двигается, а на некоторых двигается корректно, даже на одном экране», and
 * it is the same defect twice rather than two.
 *
 * Three iterations is a reasonable default for a label that is *decorated* by
 * scrolling. It is the wrong one for a label that can only be read by
 * scrolling, which is the only case this component ever applies to — it
 * measures first, and a line that fits is left alone entirely. A line nobody
 * can finish reading is worse than a line that keeps moving.
 *
 * The cost is a perpetual animation, and it has one consequence worth knowing
 * about: a Compose test that composes an overflowing line will never see an
 * idle clock, so `waitForIdle` and every assertion that calls it hang unless
 * the test sets `mainClock.autoAdvance = false`. `MarqueeTextTest` does, and
 * says so.
 *
 * **The width comes from the line itself, and must not come from a
 * [BoxWithConstraints].** Wrapping this in one is the obvious way to learn the
 * width and it crashed the app: a `BoxWithConstraints` is a `SubcomposeLayout`,
 * and a `SubcomposeLayout` cannot answer «how tall would you be at this width».
 * Asking throws `IllegalStateException: Asking for intrinsic measurements of
 * SubcomposeLayout layouts is not supported` — on the main thread, from a
 * `Layout` nobody here wrote. Nothing in this repository spells
 * `IntrinsicSize`, which is what made it look safe; Material spells it inside
 * its own rows, so the question arrives without any call site mentioning it,
 * and in a release build the stack names neither this file nor its caller.
 * [onSizeChanged] answers the same question from the layout this composable is
 * already in, and a plain layout answers an intrinsic query as it always did.
 *
 * The price is one frame: until the line has been measured once its width is
 * [Constraints.Infinity], nothing can overflow it, and a line that will scroll
 * is drawn still. That is a frame of not-yet-moving text, which is what the
 * first frame of a marquee looks like anyway.
 *
 * In a `Row` give it a `weight`, as an ellipsised `Text` there would also need,
 * or it is measured at whatever width it asks for, nothing can overflow, and it
 * quietly becomes an ordinary line of text.
 *
 * @param style must be the style the line is drawn in — it is what the string
 *   is measured with, and a mismatch measures one font and draws another.
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    fadeWidth: Dp = MarqueeFadeWidth,
    style: TextStyle = LocalTextStyle.current,
) {
    val measurer = rememberTextMeasurer()
    var available by remember { mutableIntStateOf(Constraints.Infinity) }

    val scrolls = remember(text, style, available) {
        overflows(
            measured = measurer.measure(
                text = text,
                style = style,
                // Measuring, not drawing: this is the width the string wants on
                // one unbroken line, which is the number the decision needs.
                maxLines = 1,
                softWrap = false,
            ).size.width,
            available = available,
        )
    }

    Text(
        text = text,
        color = color,
        textAlign = textAlign,
        style = style,
        // One line by definition — this component's whole subject is the line
        // that has to stay one line. It scrolls instead of being cut.
        maxLines = 1,
        softWrap = false,
        // Clip rather than Ellipsis while scrolling: the marquee's own
        // window is the clip, and an ellipsis inside unbounded width would
        // never be reached anyway.
        overflow = if (scrolls) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = modifier
            // Outermost of the three, so that it reports the window rather than
            // the text: once the marquee is on, the text inside it is as wide as
            // the string, and only this node is still the width the row gave.
            .onSizeChanged { available = it.width }
            .then(
                if (scrolls) {
                    Modifier
                        .fadingEdges(fadeWidth)
                        // Not the default three: see the note above. A line
                        // only reaches here because it cannot be read at the
                        // width it was given, so there is no reading of it that
                        // finishes in three passes and then wants stillness.
                        .basicMarquee(iterations = Int.MAX_VALUE)
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * Whether a line of [measured] pixels has to scroll to be read in [available].
 *
 * Its own function because it is the whole decision and the only part of this
 * that can be checked without composing anything. `Constraints.Infinity` is the
 * case worth naming: a text with no upper bound on its width cannot overflow,
 * and treating that enormous number as a width would set every line scrolling.
 */
internal fun overflows(measured: Int, available: Int): Boolean =
    available != Constraints.Infinity && measured > available

package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * forever. The string is measured against the width the box actually has
 * instead.
 *
 * The fade is applied *outside* the marquee, so it masks the window the text
 * scrolls through rather than travelling with the text.
 *
 * Wraps in a [BoxWithConstraints], which is the one thing it does that the
 * [Text] shim does not: the constraints are what the measurement needs. In a
 * `Row` give it a `weight`, as an ellipsised `Text` there would also need, or
 * it is handed `Constraints.Infinity`, nothing can overflow, and it quietly
 * becomes an ordinary line of text.
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

    BoxWithConstraints(modifier = modifier) {
        val available = constraints.maxWidth
        val scrolls = remember(text, style, available) {
            overflows(
                measured = measurer.measure(
                    text = text,
                    style = style,
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
            maxLines = 1,
            softWrap = false,
            // Clip rather than Ellipsis while scrolling: the marquee's own
            // window is the clip, and an ellipsis inside unbounded width would
            // never be reached anyway.
            overflow = if (scrolls) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (scrolls) {
                Modifier
                    .fadingEdges(fadeWidth)
                    .basicMarquee()
            } else {
                Modifier
            },
        )
    }
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

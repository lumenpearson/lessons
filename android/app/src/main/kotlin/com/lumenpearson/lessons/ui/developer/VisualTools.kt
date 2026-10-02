package com.lumenpearson.lessons.ui.developer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.data.developer.DeveloperTool
import kotlin.math.ceil

/**
 * The developer mode's tools that change how the whole app draws (#237), as
 * the composition reads them.
 *
 * Process-wide snapshot state, like `TranslationMode`, and for the same
 * reason: what reads it is every piece of text in the app, through
 * `AppCorrections`, and the root of the window — neither of which has a view
 * model to be handed anything by. `MainActivity` writes it from the mode's
 * state, which is already empty of tools whenever the access does not stand.
 */
internal object VisualTools {

    /** An 8 dp grid over the whole window. */
    var layoutGrid by mutableStateOf(false)

    /** Every string the app draws, longer and bracketed; see [stretched]. */
    var stretchedStrings by mutableStateOf(false)

    /** The text scale past the settings' largest; see [LargeTextScale]. */
    var largeText by mutableStateOf(false)

    fun follow(tools: Set<DeveloperTool>) {
        layoutGrid = DeveloperTool.LAYOUT_GRID in tools
        stretchedStrings = DeveloperTool.STRETCHED_STRINGS in tools
        largeText = DeveloperTool.LARGE_TEXT in tools
    }
}

/**
 * Twice the designed size: past the largest step «Оформление» offers, which is
 * where a row that only just fits at the largest step stops fitting.
 */
internal const val LargeTextScale = 2f

/** How much longer [stretched] makes a string: two fifths, about what Russian adds to English. */
private const val StretchFraction = 0.4

private const val StretchPad = '·'
private const val OpenMark = '⟦'
private const val CloseMark = '⟧'

/**
 * [text], two fifths longer and between ⟦ and ⟧ — the developer mode's
 * pseudo-localisation.
 *
 * The brackets are the point. A string cut at its end loses the ⟧, one cut at
 * its start loses the ⟦, and an ellipsis shows where the cut was: clipping is
 * visible at a glance on every screen, before a translation or a long subject
 * name finds it. The padding goes after the text, so a format pattern keeps
 * every placeholder where it was and still formats; a string of one line stays
 * one line.
 */
internal fun stretched(text: String): String {
    if (text.isEmpty()) return text
    val pad = ceil(text.length * StretchFraction).toInt()
    return buildString(text.length + pad + 2) {
        append(OpenMark)
        append(text)
        repeat(pad) { append(StretchPad) }
        append(CloseMark)
    }
}

/** The grid's step, the spacing unit the design system lays everything out in. */
private val GridStep = 8.dp

/** Every eighth line stronger, so a 64 dp block can be counted rather than guessed. */
private const val MajorEvery = 8

private val MinorLine = Color(0x2EFF00FF)
private val MajorLine = Color(0x66FF00FF)

/**
 * Draws [content] and, while [VisualTools.layoutGrid] is on, an 8 dp grid over
 * it. Magenta, because nothing in the app's palette is, so a line of the grid
 * is never mistaken for a line of the layout.
 */
@Composable
internal fun DeveloperOverlay(content: @Composable () -> Unit) {
    val grid = VisualTools.layoutGrid
    Box(
        modifier = if (grid) Modifier.drawWithContent {
            drawContent()
            val step = GridStep.toPx()
            var index = 0
            var x = 0f
            while (x <= size.width) {
                val color = if (index % MajorEvery == 0) MajorLine else MinorLine
                drawLine(color, Offset(x, 0f), Offset(x, size.height))
                x += step
                index++
            }
            index = 0
            var y = 0f
            while (y <= size.height) {
                val color = if (index % MajorEvery == 0) MajorLine else MinorLine
                drawLine(color, Offset(0f, y), Offset(size.width, y))
                y += step
                index++
            }
        } else {
            Modifier
        },
    ) {
        content()
    }
}

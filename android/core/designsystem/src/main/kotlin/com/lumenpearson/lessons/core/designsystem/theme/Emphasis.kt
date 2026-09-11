package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The one rule for weight among peers: bold is a state, not a style.
 *
 * Essentials sets the selected segment of a picker in bold and leaves the rest
 * at the regular weight, and that is the whole of its accent system for text —
 * no underline, no second colour that has to survive every wallpaper palette.
 * Weight works where colour cannot: it reads on a primary fill, on a tinted row
 * and in greyscale, and one heavier label in a row of seven is found before any
 * of them is read. The corollary is that among peers nothing is bold for looks
 * alone, or the state stops meaning anything. So every text that has a
 * selected, current or focused variant — a segment, a tab, a day, a chip, a
 * field label — takes its style through here rather than choosing a weight of
 * its own, and the rule lives in one line. Headlines and buttons are not peers
 * of anything and keep the weights their components give them.
 *
 * @param active the element is the selected, current or focused one.
 * @param resting the weight of its siblings. [FontWeight.Normal] by default,
 *   which is the clearest contrast; pass the scale's own [FontWeight.Medium]
 *   where the text is a row title that has to keep matching the row titles
 *   around it while it is *not* the one.
 */
fun TextStyle.emphasised(active: Boolean, resting: FontWeight = FontWeight.Normal): TextStyle =
    copy(fontWeight = if (active) FontWeight.Bold else resting)

package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.modifier.jiggling

/** Width of an icon-only item, and the height of every item. */
internal val ItemSize: Dp = 48.dp

/**
 * What a label is counted as when deciding whether labels fit on the row at
 * all — see [CompactScreenWidthDp] — and the least the row's spare width is
 * taken to be. Not the width a label is drawn at: that is its own (#242).
 */
internal val LabelWidth: Dp = 80.dp

/** Gap between two items while the toolbar is expanded. */
internal val ItemGap: Dp = 8.dp

/** Above this text scale the label is dropped rather than squeezed. */
internal const val LabelFontScaleLimit = 1.25f

/**
 * Below this width four slots plus a label do not fit.
 *
 * Essentials drops the label below 400 dp, which is wider than most phones in
 * portrait: on a 360 dp screen — a Pixel 8, say — that hides the label
 * permanently, so the expanding pill that is the whole point of the component
 * never appears on the device it was written for. Four slots need
 * 3×48 + 3×8 spacing + 48 + 80 = 296 dp inside 32 dp of margin, so 328 dp is the
 * real floor and 330 is it with a little air.
 *
 * The action button counts as a slot. It is drawn outside the pill but it is
 * drawn on the same row, and a pill that fits only because the thing beside it
 * was not counted does not fit.
 */
internal const val CompactScreenWidthDp = 330

/** Diameter of the badge dot. */
internal val BadgeSize: Dp = 8.dp

/** The padding on either side of a title in the toolbar's standard mode. */
internal val TitlePadding: Dp = 8.dp

/**
 * From this smallest width the window is a tablet's — Material's medium window
 * class, and the usual line between a phone and a tablet.
 */
internal const val TabletSmallestWidthDp = 600

/**
 * How much of a tablet's window a label or a title may take before it scrolls.
 *
 * The owner's rule (#242): every button is as wide as its text, and the text
 * stops growing at 30 % of a tablet's window; on a phone it may take whatever
 * the row leaves it, so that the page's name is read rather than scrolled
 * wherever there is room for it.
 */
private const val TabletLabelShare = 0.3f

/**
 * The widest the selected tab's label may grow: the window less everything else
 * on the row — the margins, the pill's ends, every tab's icon, the gaps, and the
 * action button with its own gap. The terms are [scrollingItemsMaxWidth]'s.
 */
internal fun spareLabelWidth(screenWidth: Dp, items: Int, hasAction: Boolean): Dp {
    val rest = ToolbarSideMargin * 2 + PillPadding * 2 + ItemSize * items +
        ItemGap * (items - 1).coerceAtLeast(0) +
        if (hasAction) ItemSize + ItemGap * 2 else 0.dp
    return (screenWidth - rest).coerceAtLeast(LabelWidth)
}

/**
 * The widest a title's text may grow in the standard mode: the window less the
 * margins, the pill's ends, the back button and the gap after it, the title's
 * own padding, and the button beside the pill if there is one.
 */
internal fun spareTitleWidth(screenWidth: Dp, hasAction: Boolean): Dp {
    val rest = ToolbarSideMargin * 2 + PillPadding * 2 + ItemSize + ItemGap + TitlePadding * 2 +
        if (hasAction) ItemSize + ItemGap * 2 else 0.dp
    return (screenWidth - rest).coerceAtLeast(0.dp)
}

/**
 * The widest a label or a title is drawn before it scrolls (#242): what the row
 * can [spare] on a phone, and on a tablet no more than [TabletLabelShare] of the
 * window either.
 */
internal fun textCap(screenWidth: Dp, tablet: Boolean, spare: Dp): Dp =
    if (tablet) minOf(screenWidth * TabletLabelShare, spare) else spare

/**
 * The widest the scrolling row may be before the button beside it is pushed off
 * the screen.
 *
 * Measured from the window rather than from the parent's constraints because it
 * has to be known *before* the row is measured: the row is the thing being
 * capped. Every term is deliberately generous — the gap to the button is
 * Material's and not ours — since the cost of over-reserving is one fewer icon
 * visible, and the cost of under-reserving is a toolbar wider than the phone.
 */
@Composable
internal fun scrollingItemsMaxWidth(hasAction: Boolean): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val reserved = ToolbarSideMargin * 2 +
        PillSlack * 2 +
        if (hasAction) ItemSize + ItemGap * 2 else 0.dp
    return (screenWidth - reserved).coerceAtLeast(ItemSize)
}

/** The [LessonsFloatingToolbar] Box's own horizontal padding. */
private val ToolbarSideMargin: Dp = 16.dp

/**
 * How tall Material makes the bar when a button sits beside the pill: the
 * button's slot, sized for a medium FAB (`FloatingToolbarDefaults.FabSizeRange`,
 * internal to Material), with the 64 dp pill centred in it.
 *
 * A bar without a button keeps the same height, so the pill stands at one
 * height on every page. Wrapping the pill alone made that bar 16 dp shorter,
 * and, sitting on the bottom of the screen, it carried its pill 8 dp lower —
 * the pill dropped as settings opened and rose as they closed (#240).
 */
internal val ActionSlotHeight: Dp = 80.dp

/**
 * Material's padding inside the pill: none, because all of it is in the rows.
 *
 * `FloatingToolbarDefaults.ContentPadding` is 8 dp all round, and in the layout
 * with a button beside the pill Material scrolls its content inside it. A
 * scroll container clips to a rectangle along its axis, so anything reaching
 * an end — a documentation section scrolled half out of view, a held tab drawn
 * larger, a jiggling one — was cut off by a straight edge 8 dp in from the
 * pill's curve, and the round end the pill clips itself to was never reached.
 * So the ends' padding moved inside every row (#183), and everything that
 * leaves a row goes under the pill's own round end.
 *
 * The top and bottom moved with them, because half of it inside is worse than
 * either whole (#240). Without a button beside the pill Material balances the
 * pill's padding against the interactive insets it reads off the content's
 * alignment lines, and the back button's line said 8 dp in from the side and
 * nothing from the top — so Material padded the pill by twice the difference,
 * and every page without a button was 80 dp tall where it is 64 dp with one.
 * With [PillPadding] on all four sides the insets agree and nothing is added.
 */
internal val PillContentPadding = PaddingValues(0.dp)

/** Material's padding, drawn inside every row on all four sides; see above. */
internal val PillPadding: Dp = 8.dp

/**
 * What the cap on the scrolling row keeps back on each side of it beyond the
 * margins. It was a generous 16 dp estimate of Material's padding, when that
 * sat outside the row; [PillPadding] is now inside, so this is only what
 * the estimate had to spare, and the pill is exactly as wide as it was.
 */
private val PillSlack: Dp = 8.dp

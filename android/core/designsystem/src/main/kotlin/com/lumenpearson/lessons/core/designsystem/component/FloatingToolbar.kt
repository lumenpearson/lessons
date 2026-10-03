package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import androidx.compose.ui.zIndex
import com.lumenpearson.lessons.core.designsystem.modifier.centreInRoot
import com.lumenpearson.lessons.core.designsystem.modifier.jiggling
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import com.lumenpearson.lessons.core.designsystem.theme.springSpec
import com.lumenpearson.lessons.core.designsystem.theme.tweenSpec

/**
 * One destination of the toolbar in tabbed mode.
 *
 * @param badge draws Essentials' red dot over the icon; for "there is something
 *   new behind this tab" without spending a label on it.
 */
data class ToolbarItem(
    val icon: ImageVector,
    val label: String,
    val badge: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The button drawn beside the pill.
 *
 * @param badge as on [ToolbarItem]: a dot over the icon, for "there is something
 *   here" without a label to say what.
 * @param onClick handed the middle of the button in the root composition's
 *   coordinates. Most callers ignore it; the one that does not needs a point for
 *   an effect to start from, and the button is the only thing that knows where
 *   it ended up — the toolbar floats, and its width changes with the tab.
 */
data class ToolbarAction(
    val icon: ImageVector,
    val contentDescription: String,
    val badge: Boolean = false,
    val onClick: (at: Offset) -> Unit,
)

/**
 * The pill's test tag. The pill draws no text of its own, so its height — the
 * one thing #240 got wrong — cannot be asked of any node but this one.
 */
internal const val ToolbarPillTag = "lessons_toolbar_pill"

/** Width of an icon-only item, and the height of every item. */
private val ItemSize: Dp = 48.dp

/**
 * What a label is counted as when deciding whether labels fit on the row at
 * all — see [CompactScreenWidthDp] — and the least the row's spare width is
 * taken to be. Not the width a label is drawn at: that is its own (#242).
 */
private val LabelWidth: Dp = 80.dp

/** Gap between two items while the toolbar is expanded. */
private val ItemGap: Dp = 8.dp

/** Above this text scale the label is dropped rather than squeezed. */
private const val LabelFontScaleLimit = 1.25f

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
private const val CompactScreenWidthDp = 330

/** Diameter of the badge dot. */
private val BadgeSize: Dp = 8.dp

/** The padding on either side of a title in the toolbar's standard mode. */
private val TitlePadding: Dp = 8.dp

/**
 * From this smallest width the window is a tablet's — Material's medium window
 * class, and the usual line between a phone and a tablet.
 */
private const val TabletSmallestWidthDp = 600

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
 * The bar at the bottom: a vibrant pill floating over the content, where the
 * selected destination grows out of the row as an inverted pill carrying its
 * label.
 *
 * A port of `EssentialsFloatingToolbar` from
 * [Essentials](https://github.com/sameerasw/essentials) (MIT) — the spring, the
 * colour inversion, the 48/80 dp geometry and both of its modes: a row of tabs,
 * or a back button with a title. Three things are deliberately different, each
 * of them a bug in the original:
 *
 *  * **The empty FAB slot.** Essentials always passes `floatingActionButton`,
 *    handing it `{}` when there is no button. `HorizontalFloatingToolbar` still
 *    lays out the slot it was given, so an empty one reserves the button's width
 *    and pins the pill off-centre with a hole beside it. Here the slot is only
 *    passed when there is something to put in it.
 *  * **The gaps between collapsed items.** The spacer between two items animates
 *    to 8 dp whenever the item is not the last one — including while the toolbar
 *    is collapsed and every unselected item has animated to zero width. The
 *    collapsed pill therefore keeps `(n − 1) × 8 dp` of dead space inside it.
 *    Here the gap collapses with the items.
 *  * **The label threshold.** See [CompactScreenWidthDp].
 *
 * @param items tabbed mode: pass these and [selectedIndex].
 * @param title standard mode: pass this with [onBackClick].
 * @param expanded false collapses every unselected item into the selected one.
 * @param scrollableItems for a caller whose destinations do not fit on a phone;
 *   see [scrollingItemsMaxWidth].
 * @param action the button beside the pill — Essentials' `fabAction`. It is
 *   outside the pill rather than in it because it is not a peer of what is
 *   inside: in tabbed mode the pill is where you are and the button is where you
 *   can go, and on a sub-page the pill is the way back and the button is the one
 *   thing that page can do.
 * @param floatingActionButton the same slot, for a caller that needs to draw the
 *   button itself. [action] is the shorthand and wins if both are given.
 * @param reorderable whether a long press on a tab puts the bar into the mode
 *   where its tabs can be dragged into another order. Off by default: on the
 *   documentation the bar's order is the document's, not the reader's.
 * @param reordering whether it is in that mode now. Hoisted rather than kept
 *   inside, because leaving the mode is the caller's business as much as the
 *   bar's — a back press has to take you out of it, and a caller that navigates
 *   away has to be able to close it behind itself.
 * @param onReorderingChange raised when a long press opens the mode, and when a
 *   tap inside it closes it.
 * @param onReorder the tabs' **original** indices in the order the reader left
 *   them, raised once, when the finger lifts. Indices rather than items so that
 *   this component stays ignorant of what a tab is; the caller owns the list and
 *   maps the permutation onto it.
 *
 *   Once, and on the lift, on purpose. The icons move under the finger, but the
 *   order the rest of the app reads changes only when the gesture is finished —
 *   otherwise the page behind the bar would slide about mid-drag, and a reader
 *   who changed their mind and dragged back would have travelled through two
 *   other screens on the way.
 */
@Composable
fun LessonsFloatingToolbar(
    modifier: Modifier = Modifier,
    items: List<ToolbarItem> = emptyList(),
    selectedIndex: Int = -1,
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    expanded: Boolean = true,
    scrollBehavior: FloatingToolbarScrollBehavior? = null,
    action: ToolbarAction? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    scrollableItems: Boolean = false,
    reorderable: Boolean = false,
    reordering: Boolean = false,
    onReorderingChange: (Boolean) -> Unit = {},
    onReorder: (order: List<Int>) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val actionButton: (@Composable () -> Unit)? = when {
        action != null -> {
            { ToolbarActionButton(action) }
        }

        else -> floatingActionButton
    }

    val slots = items.size + if (actionButton != null) 1 else 0
    val hideLabel = fontScale > LabelFontScaleLimit ||
        (screenWidth < CompactScreenWidthDp && slots > 3)
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= TabletSmallestWidthDp
    val motion = LocalMotion.current

    val colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
        toolbarContentColor = scheme.onSurface,
        toolbarContainerColor = scheme.primary,
    )

    // The toolbar wraps its content, so it needs a full-width parent to be
    // centred in; without one it sits wherever its parent's alignment puts it.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = ActionSlotHeight),
        contentAlignment = Alignment.Center,
    ) {
        // The two modes cross-fade into each other and the pill resizes with a
        // spring, rather than the row of tabs being replaced by a back button
        // between one frame and the next. The bar is the one element that is on
        // screen the whole time the app is, so it is the one place where a cut
        // reads as a glitch: opening settings should look like the pill
        // *becoming* the back button, not like a different bar arriving.
        val content: @Composable RowScope.() -> Unit = {
            AnimatedContent(
                targetState = onBackClick != null,
                transitionSpec = {
                    // Going in slides from the right, coming back from the left,
                    // which is the direction the page itself travels.
                    val forward = targetState
                    val enter = fadeIn(motion.tweenSpec(ModeFadeMillis)) +
                        slideInHorizontally(motion.tweenSpec(ModeSlideMillis)) { width ->
                            if (forward) width / 3 else -width / 3
                        }
                    val exit = fadeOut(motion.tweenSpec(ModeFadeMillis)) +
                        slideOutHorizontally(motion.tweenSpec(ModeSlideMillis)) { width ->
                            if (forward) -width / 3 else width / 3
                        }
                    // The size transform owns the width, and it is the only
                    // thing that does. `Modifier.animateContentSize` used to,
                    // and it cannot be used here: it applies `clipToBounds` to
                    // the *animating* box while the pill inside is already laid
                    // out at its final width, so the morph played as the two
                    // rounded ends being wiped off rather than as the bar
                    // resizing. There is no flag to turn that clip off.
                    //
                    // `AnimatedContent` measures both modes during the
                    // transition and animates its own size between them, so the
                    // pill really is narrower mid-morph — and `clip = false`
                    // means nothing is cut while it gets there.
                    (enter togetherWith exit).using(
                        SizeTransform(clip = false) { _, _ -> motion.toolbarSizeSpring() },
                    )
                },
                label = "toolbar_mode",
            ) { backMode ->
                if (backMode) {
                    Row(
                        // Material's padding, all four sides of it, moved in
                        // here; see [PillContentPadding].
                        modifier = Modifier.padding(PillPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackAndTitle(
                            title = title,
                            onBackClick = onBackClick ?: {},
                            titleMax = textCap(
                                screenWidth = screenWidth.dp,
                                tablet = tablet,
                                spare = spareTitleWidth(screenWidth.dp, actionButton != null),
                            ),
                        )
                    }
                } else {
                    ToolbarItems(
                        items = items,
                        selectedIndex = selectedIndex,
                        expanded = expanded,
                        // Every tab the same width while they are being
                        // arranged: the drag arithmetic in `ToolbarReorder`
                        // counts slots of one pitch, and one wide item among
                        // narrow ones would make «how many slots has this
                        // travelled» depend on the direction of travel. It also
                        // looks right — in this mode you are arranging icons,
                        // not reading where you are.
                        hideLabel = hideLabel || reordering,
                        scrollable = scrollableItems,
                        hasAction = actionButton != null,
                        reorderable = reorderable,
                        reordering = reordering,
                        onReorderingChange = onReorderingChange,
                        onReorder = onReorder,
                        labelMax = textCap(
                            screenWidth = screenWidth.dp,
                            tablet = tablet,
                            spare = if (scrollableItems) {
                                scrollingItemsMaxWidth(actionButton != null) - PillPadding * 2 - ItemSize
                            } else {
                                spareLabelWidth(screenWidth.dp, items.size, actionButton != null)
                            },
                        ),
                    )
                }
            }
        }

        // Nothing but the tag: the mode morph is animated by the size transform
        // above, and within a mode the pill's width already follows its tabs,
        // which animate their own widths on a spring.
        val pillModifier = Modifier.testTag(ToolbarPillTag)

        // Two call sites rather than one with a nullable argument: the overload
        // without the slot is what keeps a toolbar with no action button centred.
        if (actionButton != null) {
            HorizontalFloatingToolbar(
                modifier = pillModifier,
                expanded = expanded,
                colors = colors,
                contentPadding = PillContentPadding,
                scrollBehavior = scrollBehavior,
                floatingActionButton = actionButton,
                content = content,
            )
        } else {
            HorizontalFloatingToolbar(
                modifier = pillModifier,
                expanded = expanded,
                colors = colors,
                contentPadding = PillContentPadding,
                scrollBehavior = scrollBehavior,
                content = content,
            )
        }
    }
}

/**
 * The row of destinations inside the pill, optionally able to scroll.
 *
 * A phone fits about three items beside the action button — see
 * [CompactScreenWidthDp] — and the documentation has eight sections, all of
 * which the bar is the only way to reach. The alternatives were both worse than
 * scrolling: a window that slid across the list would move a destination out
 * from under the finger that was about to press it, and hiding the overflow
 * behind a "more" item would make "which sections are there" a question the
 * reader has to navigate to answer.
 *
 * The width cap is what makes this safe rather than clever. `horizontalScroll`
 * measures its child unbounded and then takes whatever width it is *allowed*,
 * so without a ceiling the pill would ask for eight items' worth and leave the
 * button beside it nowhere to go.
 */
@Composable
private fun ToolbarItems(
    items: List<ToolbarItem>,
    selectedIndex: Int,
    expanded: Boolean,
    hideLabel: Boolean,
    scrollable: Boolean,
    hasAction: Boolean,
    reorderable: Boolean = false,
    reordering: Boolean = false,
    onReorderingChange: (Boolean) -> Unit = {},
    onReorder: (order: List<Int>) -> Unit = {},
    labelMax: Dp = LabelWidth,
) {
    val scrollState = rememberScrollState()
    val view = rememberHapticView()
    val density = LocalDensity.current

    // The gesture's own state, and it is deliberately not hoisted: a half-
    // finished drag is not something any caller can do anything useful with,
    // and the one fact that outlives the gesture — the new order — is raised
    // when it ends.
    //
    // `held` is an index into the *working* order rather than into `items`,
    // because the row is redrawn from `working` the moment the finger moves.
    //
    // It is keyed on the order itself rather than on the number of items,
    // because a permutation only means anything beside the list it permutes.
    // Hand the bar a list in a different order and the indices it is holding
    // describe nothing — applied anyway, they reorder an order, and what the
    // reader sees is their drag jumping somewhere nobody asked for.
    //
    // The caller in `:app` latches the list for the length of the mode, so it
    // never swaps one mid-gesture; what no caller can avoid is the frame the
    // mode closes on. There the committed list and the reset of this
    // permutation arrive one after the other — the list in the composition, the
    // reset in a `LaunchedEffect` afterwards — and the frame between them drew
    // the old order. Keying them together makes it one step, and leaves the
    // rest of this function true of any caller rather than of that one.
    var working by remember(items.map { it.label }) { mutableStateOf(items.indices.toList()) }
    var held by remember { mutableStateOf(-1) }
    var dragPx by remember { mutableFloatStateOf(0f) }

    // Leaving the mode forgets an interrupted drag. Without this a caller that
    // closes the mode mid-gesture — a back press, a navigation — would leave
    // `held` pointing at a tab nothing is dragging, and the next long press
    // would resume a drag the reader had abandoned.
    LaunchedEffect(reordering) {
        if (!reordering) {
            held = -1
            dragPx = 0f
            working = items.indices.toList()
        }
    }

    val slotPx = with(density) { (ItemSize + ItemGap).toPx() }
    val landing = if (held >= 0) dropIndex(held, dragPx, slotPx, items.size) else -1

    // The selection moves as one pill sliding along the row (#258), so the row
    // keeps where each tab is and the pill between them. See [SelectionPill].
    val motion = LocalMotion.current
    val tabBounds = remember { mutableStateMapOf<String, Rect>() }
    val pill = remember { SelectionPill() }
    val selectedLabel = items.getOrNull(selectedIndex)?.label
    pill.follow(selectedLabel, slide = motion.enabled && !reordering, bounds = tabBounds)
    LaunchedEffect(selectedLabel) { pill.run(motion.pillSpring()) }
    val pillColor = tabContainerColor(selected = true, held = false, scheme = MaterialTheme.colorScheme)

    // Put the current destination in view before the bar is first drawn.
    //
    // Not an animation, and not a nicety: the caller that scrolls draws a fresh
    // bar for every page — each one is a different slot of the shell's own
    // `AnimatedContent` — so this runs on first composition every time, and an
    // animated scroll would be a bar visibly sliding to where it should already
    // have been, underneath a page that is itself still sliding.
    //
    // The offset is approximate, because the exact one depends on which item is
    // wearing its label. One item early is the right way to be wrong: it leaves
    // the destination before the current one visible, which is the one a reader
    // is most likely to want next.
    if (scrollable) {
        val density = LocalDensity.current
        LaunchedEffect(selectedIndex) {
            if (selectedIndex < 0) return@LaunchedEffect
            val target = with(density) { ((ItemSize + ItemGap) * (selectedIndex - 1)).toPx() }
            scrollState.scrollTo(target.coerceAtLeast(0f).toInt())
        }
    }

    Row(
        modifier = if (scrollable) {
            Modifier
                .widthIn(max = scrollingItemsMaxWidth(hasAction))
                .horizontalScroll(scrollState)
                // After the scroll, so it is the scrolling content that is
                // padded and the window it scrolls in reaches the pill's edge:
                // a section scrolled half out of view goes under the pill's
                // round end rather than being cut off 8 dp inside it (#183).
                .padding(PillPadding)
        } else {
            Modifier.padding(PillPadding)
        }.then(pill.draw(tabBounds, pillColor)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        working.forEachIndexed { slot, original ->
            val item = items[original]
            // Drawn where the drag has put it rather than where the list says:
            // the held tab follows the finger, and everything between it and
            // where it is going slides one slot the other way to open the gap.
            val shift = if (held >= 0) shiftSlots(slot, held, landing) else 0
            // Keyed by the tab, not by the slot it is in. Composed by position,
            // a tab's animated offset belonged to its *slot*: the drop reordered
            // the row and cleared the offsets in one frame, so every slot that
            // changed hands drew its new tab at the old tab's offset and then
            // sprang it home — the dropped tab visibly jumped away from where
            // the finger left it before settling there (#180). Keyed, the state
            // travels with the tab, and `ToolbarTab` animates where the tab
            // *is* rather than how far it is from its slot.
            key(item.label) {
                ToolbarTab(
                    item = item,
                    selected = original == selectedIndex,
                    wearsDisc = pill.wearsDisc(selected = original == selectedIndex),
                    expanded = expanded,
                    hideLabel = hideLabel,
                    // The tab's own index, not its slot's: a phase that followed
                    // the slot would jump every time the tab changed places.
                    jigglePhase = original,
                    reordering = reordering,
                    held = slot == held,
                    slot = slot,
                    slotPx = slotPx,
                    offsetPx = if (slot == held) dragPx else shift * slotPx,
                    arrangeable = reorderable,
                    labelMax = labelMax,
                    pillOver = { pill.over(item.label, tabBounds) },
                    onPlaced = { at -> tabBounds.record(item.label, at) },
                    onOpenArranging = { onReorderingChange(true) },
                    onExitReorder = { onReorderingChange(false) },
                    onDragStart = {
                        // Every pick-up, of every tab. This used to be played
                        // only by the long press that opens the mode, and inside
                        // the mode a tab is picked up by a sideways move that
                        // nothing was felt for — so carrying any tab but the
                        // selected one was silent, the selected one being felt
                        // only through the shell's page-change tap on the drop
                        // (#225). The long press runs onOpen and then this in
                        // the same event, so it is still felt exactly once.
                        // `tap` rather than `press`: a state change, not a button.
                        LessonsHaptics.tap(view)
                        held = slot
                        dragPx = 0f
                    },
                    // Not past either end of the row. dropIndex parks a finger
                    // that has left the bar on the end slot anyway, so anything
                    // further was only the tab drawn out under the pill's round
                    // end, cut in half there (#226).
                    onDrag = { delta ->
                        dragPx = (dragPx + delta).coerceIn(
                            -held * slotPx,
                            (working.lastIndex - held) * slotPx,
                        )
                    },
                    onDragEnd = {
                        val from = held
                        val to = landing
                        held = -1
                        dragPx = 0f
                        if (from >= 0 && to != from) {
                            // A notch as it lands, so a drop that changed the
                            // order feels different from one that did not.
                            LessonsHaptics.tick(view)
                            working = moveItem(working, from, to)
                            onReorder(working)
                        }
                    },
                )
            }
            // Keyed by the slot, as the tab is by itself: see [ToolbarGap].
            if (slot < working.lastIndex) key(GapKey to slot) { ToolbarGap(expanded) }
        }
    }
}

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
 * A tab's disc. Unselected it is transparent: it used to be the pill's own
 * colour, invisible everywhere except where a carried tab, drawn above its
 * neighbours and scaled up, passed over the selected tab's white disc — and
 * there it showed as a dark bite out of it (#226). Carried, it gets a body of
 * its own, the action button's pair, so it can be seen over the white disc.
 *
 * A carried body is glass, [HeldBodyAlpha] of it, and so is the selected
 * tab's white disc while that tab is the one carried. The row's pitch is
 * hardly wider than the carried tab, and a tab making room slides from under
 * one side of it to under the other: behind an opaque body that slide was
 * never seen, and the tab seemed to vanish and reappear a slot away (#259).
 *
 * Transparent as the selected disc's own colour, not as `Color.Transparent`:
 * the disc fades between the two, and a colour animation moves lightness and
 * alpha apart, so a fade to transparent black went through a half-transparent
 * grey. In the light theme that was a grey pill on the way out and a dark disc
 * on the way in (#254); with one colour on both ends, only the alpha moves.
 */
internal fun tabContainerColor(selected: Boolean, held: Boolean, scheme: ColorScheme): Color = when {
    selected && held -> scheme.background.copy(alpha = HeldBodyAlpha)
    selected -> scheme.background
    held -> scheme.primaryContainer.copy(alpha = HeldBodyAlpha)
    else -> scheme.background.copy(alpha = 0f)
}

/**
 * How much of a carried tab's body is drawn; see [tabContainerColor]. Enough
 * to read as the tab's own body over the bar and over the white disc, little
 * enough that a white icon beneath it still stands out about twice as bright
 * as the body around it.
 */
private const val HeldBodyAlpha = 0.6f

/** @see tabContainerColor */
internal fun tabContentColor(selected: Boolean, held: Boolean, scheme: ColorScheme): Color = when {
    selected -> scheme.primary
    held -> scheme.onPrimaryContainer
    else -> scheme.background
}

/** Keeps a gap's key from ever equalling a tab's, which is its label. */
private const val GapKey = "toolbar_gap"

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
private fun scrollingItemsMaxWidth(hasAction: Boolean): Dp {
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
private val ActionSlotHeight: Dp = 80.dp

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
private val PillContentPadding = PaddingValues(0.dp)

/** Material's padding, drawn inside every row on all four sides; see above. */
private val PillPadding: Dp = 8.dp

/**
 * What the cap on the scrolling row keeps back on each side of it beyond the
 * margins. It was a generous 16 dp estimate of Material's padding, when that
 * sat outside the row; [PillPadding] is now inside, so this is only what
 * the estimate had to spare, and the pill is exactly as wide as it was.
 */
private val PillSlack: Dp = 8.dp

/**
 * One tab: an icon that grows into an inverted pill with a label when selected.
 *
 * ### One gesture, as on an iOS home screen
 *
 * A long press opens the arranging mode **and picks the tab up**: the finger
 * that pressed goes on to drag, without lifting (#181). Inside the mode a
 * sideways move past the touch slop picks a tab up too, and a tap still closes
 * the mode.
 *
 * It used to be two touches, on the ground that one gesture means a tap
 * detector and a long-press-drag detector on one node, and which of them sees
 * an event turns on which consumes it first. That is true, and it is settled
 * here rather than avoided: the arranging detector is the outermost modifier and
 * reads on [PointerEventPass.Initial], which reaches it before the
 * `combinedClickable` inside, and the moment it has picked a tab up it consumes
 * every change of that pointer — so the clickable sees a consumed stream,
 * cancels its press and fires no tap on the lift. The clickable keeps no long
 * press of its own, which is what used to swallow the rest of the gesture
 * (`consumeUntilUp`); the long press stays reachable by TalkBack as a semantics
 * action. Checked on an emulator with a real pointer.
 *
 * @param held whether this is the tab under the finger right now. It is drawn
 *   above its neighbours and does not jiggle: an object you are holding is
 *   steady, and everything else is what is loose.
 * @param slot where the row has this tab now, and [slotPx] the row's pitch.
 * @param offsetPx where to draw it, along the row, relative to its slot.
 * @param arrangeable whether a long press on it may open the arranging mode.
 */
@Composable
private fun ToolbarTab(
    item: ToolbarItem,
    selected: Boolean,
    wearsDisc: Boolean = selected,
    expanded: Boolean,
    hideLabel: Boolean,
    jigglePhase: Int = 0,
    reordering: Boolean = false,
    held: Boolean = false,
    slot: Int = 0,
    slotPx: Float = 0f,
    offsetPx: Float = 0f,
    arrangeable: Boolean = false,
    labelMax: Dp = LabelWidth,
    pillOver: () -> Rect? = { null },
    onPlaced: (Rect) -> Unit = {},
    onOpenArranging: () -> Unit = {},
    onExitReorder: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()
    val visible = expanded || selected

    // The gesture callbacks, read through a state rather than captured.
    //
    // This is not ceremony. `Modifier.pointerInput` keyed on `Unit` starts its
    // coroutine once and never restarts it, and — because the element compares
    // equal on the key alone — the node is never even handed the newer lambda.
    // Whatever the drag detector closed over on the composition that opened
    // the mode is what it still calls minutes later. `held` and
    // `dragPx` survived that, being state reads; the landing slot did not, and
    // it is a plain `val` computed in the caller's composition. So every drop
    // reported the slot the tab had *before* the finger moved, which is no
    // slot at all: the order came back unchanged and the drag did nothing.
    //
    // On the screen it looked like it worked the whole time, because the icons
    // slide from a value recomputed every frame. Only the drop was stale.
    // `ToolbarDragTest` drags off the end of the bar and reads what is
    // reported, which is the only place the two can be told apart.
    //
    // Restarting the coroutine instead — a changing `pointerInput` key — would
    // cancel the gesture under the finger every time the drag moved a pixel.
    val currentReordering by rememberUpdatedState(reordering)
    val currentOpenArranging by rememberUpdatedState(onOpenArranging)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)

    val motion = LocalMotion.current
    val itemWidth by animateDpAsState(
        targetValue = if (visible) ItemSize else 0.dp,
        animationSpec = motion.toolbarSpring(),
        label = "toolbar_item_width",
    )
    // As wide as the label, not a fixed 80 dp: «Календарь» in bold is wider
    // than that at the app's larger text scales and at a system font scale
    // from about 1.1, and a label that does not fit is a marquee that never
    // stops — mid-lap it read «› Кален…» (#227). Not held to 80 dp either, the
    // floor #227 kept: «Today» sat in a pill twice its width (#242). Never
    // wider than [labelMax], past which it scrolls.
    val labelStyle = MaterialTheme.typography.labelLarge.emphasised(active = true)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelFits = remember(item.label, labelStyle, density, labelMax) {
        // A measurement, not a line on screen: one line is what the label is drawn on.
        val px = measurer.measure(item.label, labelStyle, maxLines = 1, softWrap = false).size.width
        // A pixel of slack for the round trip through dp.
        with(density) { (px + 1).toDp() }.coerceAtMost(labelMax)
    }
    val labelWidth by animateDpAsState(
        targetValue = if (selected && !hideLabel) labelFits else 0.dp,
        // Without the bounce when the label goes because the tabs are being
        // arranged. The long press that drops it is now also the start of a
        // drag (#181), and a bouncing label dips below nothing: the tab is
        // briefly narrower than an icon, and a neighbour already sliding over
        // to open the gap is carried past the end of the row and out under the
        // pill's edge, for a third of a second, mid-gesture.
        animationSpec = if (reordering) motion.toolbarSettleSpring() else motion.toolbarSpring(),
        label = "toolbar_label_width",
    )

    if (itemWidth <= 0.dp && !selected) return

    // Where the tab *is* along the row, animated; not how far it is from its
    // slot. The two are the same until the drop, and then only this one is
    // continuous: the drop moves the tab to another slot and zeroes its offset
    // in the same frame, so an animated offset started from the drag's last
    // value on a slot that had just moved — and the tab jumped by the distance
    // it had travelled before springing back (#180). An absolute position does
    // not move when the slot and the offset trade places, and the offset the
    // tab is drawn with is recovered from it below.
    //
    // Slides on the same spring the widths use while the tabs are being
    // arranged, so the row rearranging looks like the row rearranging. Not for
    // the held tab, which follows a finger — a spring between the finger and
    // the icon is lag — and not outside the mode, where the pitch is not even
    // (the selected tab wears its label) and an order that arrives from storage
    // is a fact to draw, not a movement to show.
    val position by animateFloatAsState(
        targetValue = slot * slotPx + offsetPx,
        animationSpec = if (held || !reordering) snap() else motion.toolbarOffsetSpring(),
        label = "toolbar_item_position",
    )
    val placement = if (held) offsetPx else position - slot * slotPx

    // One source for the press and the highlight, which are deliberately two
    // modifiers in two places. The press is read on the tab's whole box — a
    // square on an icon, the pill on the selected tab — so a thumb landing in a
    // corner still counts. The highlight is drawn inside the `Surface`, where
    // its `CircleShape` clips it into a circle. While both were the one
    // `combinedClickable`, the ripple and the focus layer were drawn before the
    // Surface's clip, i.e. as the whole rectangle: a plain square under a tab in
    // the middle of the bar, and a rounded one only at either end, where the
    // bar's own pill happened to cut it. The ripple still grows from wherever the
    // finger went down, corner included, and is clipped to the circle.
    val interactionSource = remember { MutableInteractionSource() }

    // `Surface` and `combinedClickable` rather than `IconButton`, and the reason
    // is the long press. Material's `IconButton` has no `onLongClick`, and
    // adding a detector of one's own to the modifier handed to it does not work:
    // it applies its own `clickable` *after* that modifier, so the press is
    // claimed before anything passed in can see it. This was written with an
    // `IconButton` first and the long press simply never arrived.
    //
    // What `IconButton` was giving is reproduced rather than approximated: a
    // circular shape, which on a box wider than it is tall is the pill the
    // selected tab wears, and the same two colour pairs.
    // At rest the selected tab wears its own disc. While the selection moves,
    // no tab does: the row draws one pill sliding from the old tab to the new,
    // and each tab it passes over is drawn the selected way where it is under
    // the pill and the unselected way where it is not ([pillOver]). Whether
    // this tab wears its disc is the row's to say ([wearsDisc]), so that a
    // slide recomposes the two tabs it moves between and none of the rest.
    //
    // Twice it was otherwise. The disc flipped on the frame the selection
    // changed, and the tab losing it went on being as wide as its label with
    // nothing behind it (#249). Then both colours faded instead — but a
    // selected tab is an unselected one inverted, the bar's colour on white
    // against white on the bar's colour, so halfway each icon was the colour
    // of its own disc and the tab read as an empty pill for a frame or two
    // (#258). Under a sliding pill no pixel is ever a blend of the two.
    //
    // The carried tab's body still fades, for the drop: it has to be there the
    // moment it is picked up, or it bites the white disc it passes over again
    // (#226), and it leaves over a quarter of a second.
    val bodySpec: FiniteAnimationSpec<Color> = if (held) snap() else motion.tweenSpec(TabColorMillis)
    val bodyColor by animateColorAsState(
        targetValue = tabContainerColor(selected = false, held = held, scheme = scheme),
        animationSpec = bodySpec,
        label = "toolbar_tab_container",
    )
    val bodyContent by animateColorAsState(
        targetValue = tabContentColor(selected = false, held = held, scheme = scheme),
        animationSpec = bodySpec,
        label = "toolbar_tab_content",
    )
    Surface(
        shape = CircleShape,
        color = if (wearsDisc) tabContainerColor(selected = true, held = held, scheme = scheme) else bodyColor,
        contentColor = if (wearsDisc) tabContentColor(selected = true, held = held, scheme = scheme) else bodyContent,
        modifier = Modifier
            // First in the chain, outside the layer that moves and scales the
            // tab: the finger is measured in the row's coordinates, so the held
            // tab follows it one to one rather than a tenth slower at
            // [HeldScale], and nothing the tab does to itself feeds back into
            // the drag. See the note above on the one gesture.
            .then(
                if (arrangeable) {
                    Modifier
                        .pointerInput(Unit) {
                            detectArrangeGesture(
                                arranging = { currentReordering },
                                onOpen = { currentOpenArranging() },
                                onStart = { currentDragStart() },
                                onDrag = { currentDrag(it) },
                                onEnd = { currentDragEnd() },
                            )
                        }
                        .semantics {
                            if (!reordering) {
                                onLongClick {
                                    // What the pick-up plays for a finger; a
                                    // screen reader's long press picks nothing up.
                                    LessonsHaptics.tap(view)
                                    currentOpenArranging()
                                    true
                                }
                            }
                        }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                translationX = placement
                // Above its neighbours while it is being carried, so the row
                // closing behind it passes underneath rather than through it.
                if (held) {
                    scaleX = HeldScale
                    scaleY = HeldScale
                }
            }
            .zIndex(if (held) 1f else 0f)
            .jiggling(active = reordering && !held, phase = jigglePhase)
            .width(itemWidth + labelWidth)
            .height(ItemSize)
            .onPlaced { coordinates -> onPlaced(coordinates.boundsInParent()) }
            .combinedClickable(
                interactionSource = interactionSource,
                // Drawn inside the Surface instead; see [interactionSource].
                indication = null,
                onClick = {
                    LessonsHaptics.press(view)
                    // A tap inside the mode is «done», not «go there» — the
                    // same thing tapping the wallpaper does on iOS, which the
                    // caller's [ArrangingDismissLayer] does for the rest of the
                    // window. Navigating instead would leave the reader on a
                    // page they did not ask for, with the bar still wobbling
                    // behind them.
                    if (reordering) onExitReorder() else item.onClick()
                },
                role = Role.Tab,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                // Always in the chain, drawing nothing while no pill passes:
                // put in and taken out with each slide, it rebuilt the nodes
                // after it, the ripple's among them, twice per change of tab.
                .inkedUnder(
                    pill = pillOver,
                    content = tabContentColor(selected = true, held = held, scheme = scheme),
                )
                // Inside the Surface, so its CircleShape clips the ripple and the
                // focus layer to a circle on an icon and to the pill on the
                // selected tab — never the square the box is. Before the padding,
                // so it covers the whole of the shape.
                .indication(interactionSource, ripple())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(24.dp),
                )
                if (item.badge) {
                    Box(
                        modifier = Modifier
                            .size(BadgeSize)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(scheme.error),
                    )
                }
            }
            // Kept while it closes, not only while selected: the label used
            // to vanish the moment another tab was chosen, with its pill still
            // closing round the room it had left. It fades with that room now
            // (#247).
            if (labelWidth > 0.dp) {
                // Both widths spelled out, and both following the spring. The
                // icon's slot has [LabelGap] to spare round the icon, which an
                // open label takes as its gap. While the label's box took
                // "whatever is left", it took that slack however shut the label
                // was: the icon sat 4 dp off-centre beside an empty space until
                // the spring reached zero, and then jumped to the centre in one
                // frame, after everything else had stopped (#262). Now the row
                // holds the icon alone when the label is shut, the whole label
                // when it is open, and every step between.
                val open = (labelWidth / labelFits.coerceAtLeast(1.dp)).coerceIn(0f, 1f)
                Spacer(Modifier.width(LabelGap * open))
                Box(
                    modifier = Modifier
                        .width(labelWidth)
                        .clipToBounds()
                        .graphicsLayer { alpha = open }
                        // A label on its way out is not a second name for a tab
                        // that is no longer the selected one.
                        .then(if (selected) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    // Laid out at the width it is going to have, and cut by the
                    // width the spring has reached. The marquee decides whether
                    // to scroll from the width it is given, and for the first
                    // frames of every selection the pill is narrower than the
                    // label: the fading edges came on and went off a frame later,
                    // a gradient over the label on every tap (#246). At its own
                    // width it scrolls only when it truly does not fit, and the
                    // pill growing reveals it rather than squeezing it.
                    // The content colour, not the selected one: beside the
                    // sliding pill the label is drawn the unselected way, like
                    // the icon, and the pill inks it where it covers it.
                    MarqueeText(
                        text = item.label,
                        style = labelStyle,
                        color = LocalContentColor.current,
                        modifier = Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .requiredWidth(labelFits),
                    )
                }
            }
        }
    }
}

/**
 * The space after a slot of the row, collapsing with the tabs; see the note on
 * [LessonsFloatingToolbar].
 *
 * Its own composable, owned by the slot, rather than the tail of each tab: the
 * gap is between two places in the row, not a part of whichever tab is in the
 * first of them. While each tab carried its own, keying the tabs (#180) made
 * the gap travel with them — so a drop that moved the last tab into the middle
 * grew its gap from nothing, and the tab behind it landed a gap short and slid
 * the rest of the way.
 */
@Composable
private fun ToolbarGap(expanded: Boolean) {
    val gap by animateDpAsState(
        targetValue = if (expanded) ItemGap else 0.dp,
        animationSpec = LocalMotion.current.toolbarSpring(),
        label = "toolbar_item_gap",
    )
    Spacer(Modifier.width(gap))
}

/**
 * Essentials' `fabAction` button: tonal, square-ish, and flat.
 *
 * No elevation on purpose — the pill beside it has none either, and a shadow
 * under one of the two would read as the button hovering above the bar rather
 * than sitting next to it.
 */
@Composable
private fun ToolbarActionButton(action: ToolbarAction) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()
    var centre by remember { mutableStateOf(Offset.Unspecified) }

    FloatingActionButton(
        onClick = {
            LessonsHaptics.press(view)
            action.onClick(centre)
        },
        modifier = Modifier.centreInRoot { centre = it },
        containerColor = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Box {
            Icon(
                imageVector = action.icon,
                contentDescription = action.contentDescription,
                modifier = Modifier.size(24.dp),
            )
            if (action.badge) {
                Box(
                    modifier = Modifier
                        .size(BadgeSize)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(scheme.error),
                )
            }
        }
    }
}

/** Standard mode: the same inverted pill, holding a back button and a title. */
@Composable
private fun BackAndTitle(
    title: String?,
    onBackClick: () -> Unit,
    titleMax: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()

    IconButton(
        onClick = {
            LessonsHaptics.press(view)
            onBackClick()
        },
        modifier = Modifier.size(ItemSize),
        colors = IconButtonDefaults.filledIconButtonColors(
            contentColor = scheme.primary,
            containerColor = scheme.background,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            // Named, not null. The title beside it is a separate node, so a
            // screen reader announcing this button announced nothing at all.
            contentDescription = correctedString(R.string.ds_action_back),
            modifier = Modifier.size(24.dp),
        )
    }
    if (title != null) {
        Spacer(Modifier.width(ItemGap))
        // The cap and the padding go on the box, so the title is measured
        // against the room it will actually be drawn in rather than against
        // the toolbar. Same reason as the label above: a title that fits
        // should sit still. No floor: a short title used to sit in at least
        // 100 dp, and «← Sync» was as wide a pill as «← Settings» (#242).
        MarqueeText(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.background,
            modifier = Modifier
                .widthIn(max = titleMax + TitlePadding * 2)
                .padding(horizontal = TitlePadding),
        )
    }
}

/**
 * The one spring the toolbar animates with — at the reader's motion speed, and a
 * snap with animations off, like every spring here: none of them read the
 * motion settings until #246, so the bar went on springing with the switch off.
 *
 * Without a bounce. It used to overshoot, which gave the selected tab a
 * flourish of its own and pushed every tab beside it past its place and back:
 * once the selection slid as a pill (#258), each neighbour shook as the pill
 * arrived, as if struck, and the owner filmed it («дергаются после
 * затрагивания их таблеткой»). Every width in the component shares it so they
 * cannot arrive at different times — all but the one that changes on its own,
 * the label going as the tabs start to be arranged, which is
 * [toolbarSettleSpring]'s.
 */
private fun MotionSettings.toolbarSpring() = springSpec<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The spring the selected tab's label goes on as the arranging mode opens.
 *
 * [toolbarSpring]'s pace without its bounce. The long press that opens the mode
 * is also the start of a drag (#181), and the drag counts in slots of one
 * pitch: a label that overshot on its way out made its tab narrower than an
 * icon for a moment, which carried a neighbour already sliding into the gap
 * past the end of the row.
 */
private fun MotionSettings.toolbarSettleSpring() = springSpec<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/**
 * The spring the pill's width follows while it morphs between its two modes.
 *
 * Less bouncy than [toolbarSpring]: the whole bar overshooting its width reads
 * as the bar wobbling, where one tab overshooting reads as the tab landing.
 */
private fun MotionSettings.toolbarSizeSpring() = springSpec<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The spring a tab slides on while its neighbour is dragged past it.
 *
 * Stiffer and flatter than [toolbarSpring]: this one is chasing a finger that
 * is still moving, so an overshoot is a tab that goes past the gap it is
 * supposed to be opening and comes back — which reads as the row arguing with
 * the drag.
 */
private fun MotionSettings.toolbarOffsetSpring() = springSpec<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * How much bigger the tab under the finger is drawn.
 *
 * Small, because the tab is 48 dp and a tenth of that is five pixels of growth
 * at the edges — enough for «this is the one I am holding» and not enough to
 * make it collide with the neighbour it is passing.
 */
private const val HeldScale = 1.1f

/**
 * How long a tab's disc takes to fade in or out at the normal speed: about as
 * long as the label's room takes to open or close most of the way.
 */
private const val TabColorMillis = 260

/** The gap between a tab's icon and its label. */
private val LabelGap: Dp = 8.dp

/** Cross-fade and slide of the two toolbar modes. */
private const val ModeFadeMillis = 180
private const val ModeSlideMillis = 260

@Preview(name = "Floating toolbar", showBackground = true)
@Composable
private fun FloatingToolbarPreview() {
    LessonsTheme {
        LessonsFloatingToolbar(
            selectedIndex = 0,
            items = listOf(
                ToolbarItem(Icons.Rounded.Today, "Сегодня") {},
                ToolbarItem(Icons.Rounded.CalendarViewWeek, "Неделя") {},
                ToolbarItem(Icons.AutoMirrored.Rounded.MenuBook, "Задания", badge = true) {},
            ),
            action = ToolbarAction(Icons.Rounded.Settings, "Настройки") {},
        )
    }
}

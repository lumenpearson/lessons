package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

/** Width of an icon-only item, and the height of every item. */
private val ItemSize: Dp = 48.dp

/** Extra width the selected item grows by to fit its label. */
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

/** Longest a title may be in the toolbar's standard mode before it marquees. */
private val TitleWidthRange = 100.dp..250.dp

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
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    val enter = fadeIn(tween(ModeFadeMillis)) +
                        slideInHorizontally(tween(ModeSlideMillis)) { width ->
                            if (forward) width / 3 else -width / 3
                        }
                    val exit = fadeOut(tween(ModeFadeMillis)) +
                        slideOutHorizontally(tween(ModeSlideMillis)) { width ->
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
                        SizeTransform(clip = false) { _, _ -> toolbarSizeSpring() },
                    )
                },
                label = "toolbar_mode",
            ) { backMode ->
                if (backMode) {
                    Row(
                        // Material's horizontal padding, moved in here; see
                        // [PillContentPadding].
                        modifier = Modifier.padding(horizontal = PillEndPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackAndTitle(title = title, onBackClick = onBackClick ?: {})
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
                    )
                }
            }
        }

        // Nothing here: the mode morph is animated by the size transform above,
        // and within a mode the pill's width already follows its tabs, which
        // animate their own widths on a spring.
        val pillModifier = Modifier

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
                .padding(horizontal = PillEndPadding)
        } else {
            Modifier.padding(horizontal = PillEndPadding)
        },
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
                    onDrag = { delta -> dragPx += delta },
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
 * Material's padding inside the pill, less its two ends (#183).
 *
 * `FloatingToolbarDefaults.ContentPadding` is 8 dp all round, and in the layout
 * with a button beside the pill Material scrolls its content inside it. A
 * scroll container clips to a rectangle along its axis, so anything reaching
 * an end — a documentation section scrolled half out of view, a held tab drawn
 * larger, a jiggling one — was cut off by a straight edge 8 dp in from the
 * pill's curve, and the round end the pill clips itself to was never reached.
 * The ends' padding is [PillEndPadding] instead, inside every row, so the rows
 * sit exactly where they did and everything that leaves them goes under the
 * pill's own round end.
 */
private val PillContentPadding = PaddingValues(vertical = 8.dp)

/** The two ends of Material's padding, drawn inside the rows; see above. */
private val PillEndPadding: Dp = 8.dp

/**
 * What the cap on the scrolling row keeps back on each side of it beyond the
 * margins. It was a generous 16 dp estimate of Material's padding, when that
 * sat outside the row; [PillEndPadding] is now inside, so this is only what
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
    expanded: Boolean,
    hideLabel: Boolean,
    jigglePhase: Int = 0,
    reordering: Boolean = false,
    held: Boolean = false,
    slot: Int = 0,
    slotPx: Float = 0f,
    offsetPx: Float = 0f,
    arrangeable: Boolean = false,
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

    val itemWidth by animateDpAsState(
        targetValue = if (visible) ItemSize else 0.dp,
        animationSpec = toolbarSpring(),
        label = "toolbar_item_width",
    )
    val labelWidth by animateDpAsState(
        targetValue = if (selected && !hideLabel) LabelWidth else 0.dp,
        // Without the bounce when the label goes because the tabs are being
        // arranged. The long press that drops it is now also the start of a
        // drag (#181), and a bouncing label dips below nothing: the tab is
        // briefly narrower than an icon, and a neighbour already sliding over
        // to open the gap is carried past the end of the row and out under the
        // pill's edge, for a third of a second, mid-gesture.
        animationSpec = if (reordering) toolbarSettleSpring() else toolbarSpring(),
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
        animationSpec = if (held || !reordering) snap() else toolbarOffsetSpring(),
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
    Surface(
        shape = CircleShape,
        color = if (selected) scheme.background else scheme.primary,
        contentColor = if (selected) scheme.primary else scheme.background,
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
                    tint = if (selected) scheme.primary else scheme.background,
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
            if (selected && !hideLabel) {
                Spacer(Modifier.width(8.dp))
                // Marquees only if it has to. This used to scroll whatever it
                // was given, so a label that fitted animated anyway — the
                // thing the segmented picker measures to avoid, and the reason
                // that measurement is a component now.
                MarqueeText(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge.emphasised(active = selected),
                    color = scheme.primary,
                )
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
        animationSpec = toolbarSpring(),
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
        // The width range and the padding go on the box, so the title is
        // measured against the room it will actually be drawn in rather than
        // against the toolbar. Same reason as the label above: a title that
        // fits should sit still.
        MarqueeText(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.background,
            modifier = Modifier
                .widthIn(min = TitleWidthRange.start, max = TitleWidthRange.endInclusive)
                .padding(horizontal = 8.dp),
        )
    }
}

/**
 * The one spring the toolbar animates with.
 *
 * Bouncy and slow, which is what gives the selected pill its overshoot; every
 * width in the component shares it so they cannot arrive at different times —
 * all but the one that changes on its own, the label going as the tabs start
 * to be arranged, which is [toolbarSettleSpring]'s.
 */
private fun toolbarSpring() = spring<Dp>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
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
private fun toolbarSettleSpring() = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/**
 * The spring the pill's width follows while it morphs between its two modes.
 *
 * Less bouncy than [toolbarSpring]: the whole bar overshooting its width reads
 * as the bar wobbling, where one tab overshooting reads as the tab landing.
 */
private fun toolbarSizeSpring() = spring<IntSize>(
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
private fun toolbarOffsetSpring() = spring<Float>(
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

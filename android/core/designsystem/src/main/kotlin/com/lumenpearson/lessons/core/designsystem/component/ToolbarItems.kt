package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion

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
internal fun ToolbarItems(
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

/** Keeps a gap's key from ever equalling a tab's, which is its label. */
private const val GapKey = "toolbar_gap"

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

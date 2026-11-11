package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.jiggling
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.tweenSpec

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
internal fun ToolbarTab(
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

package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.tweenSpec

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
 *    and pins the pill off-centre with a hole beside it. Here there is no
 *    Material slot at all: the button is laid out beside the pill and shrinks to
 *    nothing when there is none (see [ActionSlot]), and the pill is a container
 *    of this file's own (see [PillContainer]).
 *  * **The gaps between collapsed items.** The spacer between two items animates
 *    to 8 dp whenever the item is not the last one — including while the toolbar
 *    is collapsed and every unselected item has animated to zero width. The
 *    collapsed pill therefore keeps `(n − 1) × 8 dp` of dead space inside it.
 *    Here the gap collapses with the items.
 *  * **The label threshold.** See [CompactScreenWidthDp].
 *
 * @param items tabbed mode: pass these and [selectedIndex].
 * @param title standard mode: pass this with [onBackClick].
 * @param depth how deep the page this bar is drawn for is, for a caller that
 *   keeps one bar across its pages (#264): a change to a deeper page morphs the
 *   bar in from the right, a change back from the left. By default the tabs are
 *   0 and the back button 1.
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
    depth: Int = if (onBackClick != null) 1 else 0,
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
        // What the pill shows, carried whole. The faces cross-fade into each
        // other and the pill resizes with a spring, rather than one form being
        // replaced by another between one frame and the next. The bar is the
        // one element on screen the whole time the app is, so it is the one
        // place where a cut reads as a glitch: opening settings should look like
        // the pill *becoming* the back button, not like a different bar arriving.
        //
        // Carried whole, because one bar now serves every page of the shell
        // (#264): `AnimatedContent` composes the face on its way out with the
        // lambda it is given *now*, so a face that read `items` or `title` from
        // here would leave showing the arriving page's — tabs emptied the frame
        // settings opened, the old title replaced by the new mid-fade.
        val face: PillFace = if (onBackClick != null) {
            BackFace(
                title = title,
                onBackClick = onBackClick,
                titleMax = textCap(
                    screenWidth = screenWidth.dp,
                    tablet = tablet,
                    spare = spareTitleWidth(screenWidth.dp, actionButton != null),
                ),
                depth = depth,
            )
        } else {
            tabsFace(
                items = items,
                selectedIndex = selectedIndex,
                expanded = expanded,
                // Every tab the same width while they are being arranged: the
                // drag arithmetic in `ToolbarReorder` counts slots of one pitch,
                // and one wide item among narrow ones would make «how many slots
                // has this travelled» depend on the direction of travel. It also
                // looks right — in this mode you are arranging icons, not reading
                // where you are.
                hideLabel = hideLabel || reordering,
                scrollable = scrollableItems,
                hasAction = actionButton != null,
                reorderable = reorderable,
                reordering = reordering,
                onReorderingChange = onReorderingChange,
                onReorder = onReorder,
                screenWidth = screenWidth.dp,
                tablet = tablet,
                depth = depth,
            )
        }
        val content: @Composable RowScope.() -> Unit = {
            AnimatedContent(
                targetState = face,
                // A new selection, a new label, a reorder: the same face, drawn
                // again. Only a different face — tabs for a back button, one set
                // of tabs for another — morphs.
                contentKey = { it.key },
                transitionSpec = {
                    // Going in slides from the right, coming back from the left,
                    // which is the direction the page itself travels.
                    val forward = targetState.depth >= initialState.depth
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
            ) { shown ->
                when (shown) {
                    is BackFace -> Row(
                        // Material's padding, all four sides of it, moved in
                        // here; see [PillContentPadding].
                        modifier = Modifier.padding(PillPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackAndTitle(face = shown)
                    }

                    is TabsFace -> ToolbarItems(
                        items = shown.items,
                        selectedIndex = shown.selectedIndex,
                        expanded = shown.expanded,
                        hideLabel = shown.hideLabel,
                        scrollable = shown.scrollable,
                        hasAction = shown.hasAction,
                        reorderable = shown.reorderable,
                        reordering = shown.reordering,
                        onReorderingChange = shown.onReorderingChange,
                        onReorder = shown.onReorder,
                        labelMax = shown.labelMax,
                    )
                }
            }
        }

        // One layout, whether or not there is a button beside the pill. It used
        // to be two call sites — Material's overload with a button slot and the
        // one without, because an empty slot pins the pill off-centre — and a
        // page whose button came or went changed overload, which threw the
        // whole bar away and built another: no morph could survive it, and with
        // one bar for every page (#264) the settings of a reader with no debug
        // page did exactly that. Here the pill is always the overload without a
        // slot, and the button grows in and shrinks out beside it, so the pill
        // glides to the centre rather than being rebuilt there.
        Row(
            modifier = Modifier.following(scrollBehavior),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillContainer(
                // Nothing but the tag: the morph is animated by the size
                // transform above, and within a face the pill's width already
                // follows its tabs, which animate their own widths on a spring.
                modifier = Modifier.testTag(ToolbarPillTag),
                colors = colors,
                content = content,
            )
            ActionSlot(action = action, floatingActionButton = floatingActionButton)
        }
    }
}

/** The bar riding [behavior], when there is one; Material's scroll-away, pill and button together. */
@Composable
internal fun Modifier.following(behavior: FloatingToolbarScrollBehavior?): Modifier =
    if (behavior == null) this else with(behavior) { this@following.floatingScrollBehavior() }

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

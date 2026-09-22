package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.model.HomeTab

/**
 * The tab bar's order after one drag, and the one place that arithmetic lives.
 *
 * Pulled out of the shell for the reason [shellActionKind] was: what the bar
 * hands back is a list of *indices*, and a mapping between two lists of indices
 * is wrong in a way nothing announces. Inverting it — reading the permutation as
 * «where each tab went» instead of «what is now in each place» — gives the right
 * answer for every swap of two neighbours and the wrong one for everything else,
 * which is precisely the change a three-tab bar is least likely to show.
 */

/**
 * [current] rearranged into [moved].
 *
 * @param current the order the bar was handed, which is not necessarily the
 *   stored one: the bar goes on drawing from the list it was given until the
 *   mode closes, so a second drag in the same session is relative to the same
 *   list as the first.
 * @param moved the items' original indices in the order the reader left them —
 *   `moved[0]` is the item now drawn first. A list that is not a permutation of
 *   `current.indices` is refused rather than applied: this comes out of a
 *   gesture, and half a permutation would drop a tab off the bar altogether.
 *   `HomeTab.order` would put it back on the next read, silently, somewhere
 *   the reader did not leave it.
 */
internal fun reorderTabs(current: List<HomeTab>, moved: List<Int>): List<HomeTab> =
    if (moved.sorted() == current.indices.toList()) moved.map(current::get) else current

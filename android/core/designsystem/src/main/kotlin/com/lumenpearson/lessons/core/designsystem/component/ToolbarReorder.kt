package com.lumenpearson.lessons.core.designsystem.component

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The arithmetic of dragging one tab along a row of them.
 *
 * Separated from the composable for the reason this project keeps separating
 * things: a Compose test cannot easily ask «where would a drag of 73 pixels put
 * it», and that is the half of a reorder gesture that is actually wrong when a
 * reorder gesture is wrong. Every function here is pure, takes pixels and
 * indices, and knows nothing about a pointer or a modifier.
 *
 * The row is assumed to be evenly spaced, which it is only while the bar is in
 * reorder mode — see `ToolbarItems`, which drops every label for exactly this
 * reason. One wide item among narrow ones would make «how many slots has this
 * travelled» depend on which direction it travelled in.
 */

/**
 * Where a tab picked up from [from] lands after being dragged [dragPx].
 *
 * Rounds rather than truncates, so a tab is taken to belong to the slot it is
 * *nearest*: half a slot of travel is the moment it changes place, which is the
 * same threshold the eye uses when the two icons overlap evenly.
 *
 * @param slotPx one item plus the gap after it; the pitch of the row.
 * @return an index inside `0 until count`, so a drag off either end parks at the
 *   end rather than being refused — a finger that leaves the bar is still
 *   holding something, and the something has to be somewhere.
 */
internal fun dropIndex(from: Int, dragPx: Float, slotPx: Float, count: Int): Int {
    if (count <= 1 || slotPx <= 0f) return from.coerceIn(0, (count - 1).coerceAtLeast(0))
    // Rounded away from zero rather than with `roundToInt`, which is the same
    // thing only for positive numbers: it breaks a tie towards positive
    // infinity, so exactly half a slot leftwards rounds to *nought* slots while
    // half a slot rightwards rounds to one. The threshold would be half a slot
    // going right and a hair over half going left — a gesture that is harder
    // to perform in one direction than the other, by about a pixel, for no
    // reason anybody could ever find. A test caught it; a finger would only
    // have felt it.
    val travelled = (abs(dragPx) / slotPx).roundToInt() * sign(dragPx).toInt()
    return (from + travelled).coerceIn(0, count - 1)
}

/**
 * How far the tab at [index] slides while the tab from [from] hovers over [to].
 *
 * In slots, and only ever −1, 0 or +1: the row closes the hole the dragged tab
 * left and opens one where it is going, so everything between the two shifts by
 * exactly one place, towards where the hole was. Everything outside that span
 * stays put, and the dragged tab itself is drawn under the finger rather than in
 * a slot, so it gets 0 too.
 *
 * This is what makes the gesture readable: without it the other icons would sit
 * still until the finger lifted and the whole row would then jump, which reads
 * as the drag having failed and then been undone.
 */
internal fun shiftSlots(index: Int, from: Int, to: Int): Int = when {
    index == from -> 0
    from < to && index in (from + 1)..to -> -1
    from > to && index in to..(from - 1) -> 1
    else -> 0
}

/**
 * [order] with the item at [from] moved to [to], the rest closing behind it.
 *
 * Returns the list unchanged when the move is a no-op or either index is out of
 * bounds, because the caller is a gesture: a drag that ends where it started is
 * the ordinary way to change one's mind, not an error to report.
 */
internal fun <T> moveItem(order: List<T>, from: Int, to: Int): List<T> {
    if (from == to) return order
    if (from !in order.indices || to !in order.indices) return order
    return buildList(order.size) {
        addAll(order)
        add(to, removeAt(from))
    }
}

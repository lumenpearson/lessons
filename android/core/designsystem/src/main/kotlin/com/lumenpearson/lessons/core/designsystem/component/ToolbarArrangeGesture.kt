package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Picking a tab up and carrying it, in one touch (#181).
 *
 * Outside the arranging mode the tab is picked up by holding still for a long
 * press, which also opens the mode ([onOpen] runs first, then [onStart]); inside
 * it, by moving sideways past the touch slop. Either way the same pointer then
 * drags ([onDrag], in pixels along the row) until it lifts or is cancelled
 * ([onEnd], always, in a `finally`, so a gesture cut off by the node leaving
 * the composition still puts the row back).
 *
 * Everything is read on [PointerEventPass.Initial], which reaches this detector
 * before the tab's own `combinedClickable`, and once a tab is picked up every
 * change of that pointer is consumed — the lift included. That is what keeps a
 * long press from ending in a tap: the clickable sees a consumed stream,
 * cancels its press, and does not navigate or close the mode on the lift.
 * Until a tab is picked up nothing is consumed, so a plain tap is the
 * clickable's to handle exactly as before.
 *
 * @param arranging read on every touch, not captured, because the mode opens
 *   and closes under a detector that is never restarted.
 */
internal suspend fun PointerInputScope.detectArrangeGesture(
    arranging: () -> Boolean,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val pickedUp = if (arranging()) {
        awaitSideways(down)
    } else {
        awaitHold(down).also { if (it) onOpen() }
    }
    if (!pickedUp) return@awaitEachGesture

    onStart()
    try {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.id == down.id } ?: break
            val dx = change.position.x - change.previousPosition.x
            val lifted = !change.pressed
            change.consume()
            if (lifted) break
            if (dx != 0f) onDrag(dx)
        }
    } finally {
        onEnd()
    }
}

/**
 * True when [down] was held without lifting or wandering for the long-press
 * timeout; false when it lifted (a tap) or moved first (not a hold).
 */
private suspend fun AwaitPointerEventScope.awaitHold(down: PointerInputChange): Boolean {
    val settled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
            if (!change.pressed || change.isConsumed) return@withTimeoutOrNull
            if (travelled(change, down) > viewConfiguration.touchSlop) return@withTimeoutOrNull
        }
    }
    // `null` is the timeout firing with the finger still down and still: a hold.
    return settled == null && isStillDown(down.id)
}

/** True once [down] has moved past the touch slop; false if it lifted first. */
private suspend fun AwaitPointerEventScope.awaitSideways(down: PointerInputChange): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes
            .firstOrNull { it.id == down.id } ?: return false
        if (!change.pressed || change.isConsumed) return false
        if (travelled(change, down) > viewConfiguration.touchSlop) {
            change.consume()
            return true
        }
    }
}

private fun travelled(change: PointerInputChange, down: PointerInputChange): Float =
    (change.position - down.position).getDistance()

/**
 * Whether the pointer is still pressed as of the last event this scope saw.
 *
 * Asked after the timeout, because the timeout and a lift can arrive in the same
 * frame and only the event says which came first.
 */
private fun AwaitPointerEventScope.isStillDown(id: PointerId): Boolean =
    currentEvent.changes.firstOrNull { it.id == id }?.pressed ?: false

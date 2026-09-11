package com.lumenpearson.lessons.core.designsystem.modifier

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Stops anything this element covers from receiving touches.
 *
 * For a layer drawn over content that is still composed. Compose hit-tests every
 * node under the pointer, not just the topmost one, so an opaque `Box` laid over
 * a list is opaque to the eye and completely transparent to the finger: taps
 * land on the rows behind it and drags scroll them.
 *
 * **The first press is deliberately left alone.** `Modifier.clickable` does not
 * claim the press when it arrives — it claims the *release*, once it knows the
 * gesture turned out to be a tap and not the start of a scroll. So a layer that
 * swallowed the press swallowed every click inside itself along with the ones it
 * meant to block, and the rows of a page drawn this way could not be tapped at
 * all. Everything after the press is swallowed, which is what actually keeps a
 * drag from reaching a list underneath, and the release is swallowed too unless
 * a child claimed it first — the main pass reaches children before it reaches
 * this element, so by the time it gets here a child that wanted the tap has it.
 */
fun Modifier.swallowGestures(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { change ->
                if (!change.isConsumed) change.consume()
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

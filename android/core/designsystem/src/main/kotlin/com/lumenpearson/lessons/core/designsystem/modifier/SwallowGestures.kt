package com.lumenpearson.lessons.core.designsystem.modifier

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
 * The consumption happens on the main pass, which reaches this element's own
 * children first — so whatever is *inside* the layer still scrolls and still
 * responds, and only what they leave unhandled is swallowed here.
 */
fun Modifier.swallowGestures(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Main).changes.forEach { change ->
                if (!change.isConsumed) change.consume()
            }
        }
    }
}

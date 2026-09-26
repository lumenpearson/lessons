package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Everything around the bar while its tabs are being arranged: a touch anywhere
 * here closes the mode, and reaches nothing underneath (#182).
 *
 * Drawn above the page and below the bar. Compose hit-tests siblings from the
 * top down and stops at the first one that takes the pointer, so the bar keeps
 * its own gestures — a tap on a tab still closes the mode, a drag still carries
 * one — and the page gets no touch at all while the mode is open. That is the
 * point rather than a side effect: a tap meant to close the mode must not also
 * open the lesson it landed on, and a swipe must not slide the pager out from
 * under the icons being arranged.
 *
 * The mode closes when the finger lifts, as a tap does, rather than when it
 * lands. A drag that starts here is swallowed whole and closes it the same way,
 * because the alternative is a touch that does nothing at all.
 */
@Composable
fun ArrangingDismissLayer(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Read through a state: the detector below is started once and never
    // restarted, and would otherwise go on calling the first lambda it saw.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    currentOnDismiss()
                }
            },
    )
}

package com.lumenpearson.lessons.core.designsystem.modifier

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * The middle of this node, in the window's coordinates.
 *
 * Written out four times across the theme wipe, the toolbar's action button and
 * the update sheet before it was a function, each time as the same three lines
 * and each time with its own chance of adding the corner instead of the middle.
 * Both effects that start somewhere — the circular wipe and the liquid wave —
 * take root coordinates, which is what [positionInRoot] gives.
 *
 * @param onCentre called on every layout pass, so it must be cheap and must not
 *   itself cause one.
 */
fun Modifier.centreInRoot(onCentre: (Offset) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val corner = coordinates.positionInRoot()
        onCentre(
            Offset(
                x = corner.x + coordinates.size.width / 2f,
                y = corner.y + coordinates.size.height / 2f,
            ),
        )
    }

/**
 * Where the control the finger actually landed on is.
 *
 * A settings row is a wide thing: an icon tile, a title, a subtitle, and the
 * control itself against the right edge — or, for a picker, three segments laid
 * across the bottom of it. Starting the wave at the middle of *that* puts it
 * roughly nowhere: a couple of centimetres left of the switch that was flipped,
 * or over the middle segment when the right-hand one was chosen. The eye
 * notices, because the whole point of the effect is to look like the change
 * came out from under the finger.
 *
 * So the control reports itself. The anchor that owns the effect publishes one
 * of these through [LocalControlCentre]; the switch or the segment writes its
 * own centre into it as the tap is handled, before the anchor is asked to play
 * anything. The anchor keeps its own centre as the fallback, for a control that
 * does not report — an anchor around something plain still works, it just aims
 * at the middle of what it wraps.
 *
 * Reported on tap rather than on layout because a picker has several controls
 * under one anchor and only one of them was pressed. A switch could report from
 * its layout instead, since a row holds exactly one; it reports on tap too, so
 * that there is one rule rather than two that happen to agree.
 */
@Stable
class ControlCentre {

    /** The last reported centre, or [Offset.Unspecified] before the first tap. */
    var offset: Offset by mutableStateOf(Offset.Unspecified)
        private set

    fun report(value: Offset) {
        offset = value
    }
}

/**
 * The nearest enclosing [ControlCentre], or `null` where nothing is listening —
 * which is most of the app, and why every caller treats it as optional.
 */
val LocalControlCentre = staticCompositionLocalOf<ControlCentre?> { null }

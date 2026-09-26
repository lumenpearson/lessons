package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion

/**
 * The app's one wavy progress bar: the countdown under the hero card, and any
 * screen that waits on something long enough to draw how far it has got — the
 * diary import is the second.
 *
 * One implementation because the bar is a signature of the app rather than a
 * stock control: two copies would drift apart in height, in the spring they
 * move by, and in whether they listen to the motion setting, and the first two
 * are exactly what a reader notices when one screen hands over to the next.
 *
 * With animations off the bar **snaps** and lies flat: no wave and no travel.
 * A wave that keeps moving under a switch that says «без анимаций» is the one
 * motion somebody who turned motion off will still see on every screen that
 * waits.
 *
 * @param progress how much is done, `0..1`; values outside are clamped, so a
 *   caller that overshoots by a rounding error does not draw past the end.
 * @param description what a screen reader says for the bar. `null` leaves the
 *   indicator's own semantics (a bare percentage), which is right only where
 *   the text beside the bar already says what the percentage is of.
 * @param color the wave; the theme's primary by default.
 * @param trackColor the unfilled part; the indicator's own default track.
 */
@Composable
fun LessonsWavyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    description: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
) {
    val motion = LocalMotion.current
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        // The MotionScheme installed by LessonsTheme, which is the point of
        // MaterialExpressiveTheme. If MaterialTheme.motionScheme moves, the
        // stable fallback is tween(durationMillis = 600).
        animationSpec = if (motion.enabled) {
            MaterialTheme.motionScheme.slowSpatialSpec()
        } else {
            snap()
        },
        label = "lessonsWavyProgress",
    )
    val semantic = if (description != null) {
        Modifier.semantics { contentDescription = description }
    } else {
        Modifier
    }

    if (motion.enabled) {
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = modifier
                .fillMaxWidth()
                .height(WavyBarHeight)
                .then(semantic),
            color = color,
            trackColor = trackColor,
        )
    } else {
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = modifier
                .fillMaxWidth()
                .height(WavyBarHeight)
                .then(semantic),
            color = color,
            trackColor = trackColor,
            // Flat and still: the amplitude is what draws the wave, and the
            // speed is what moves it along the bar.
            amplitude = { 0f },
            waveSpeed = 0.dp,
        )
    }
}

/** The countdown's height, which every other bar takes so the two read as one control. */
private val WavyBarHeight = 12.dp

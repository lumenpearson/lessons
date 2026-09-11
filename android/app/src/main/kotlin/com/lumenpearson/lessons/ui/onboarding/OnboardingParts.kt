package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.theme.GoogleSansFlexRounded
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.round
import kotlinx.coroutines.launch

/** Height of a first-run action, straight from the Essentials onboarding. */
internal val ActionHeight: Dp = 56.dp

/**
 * The bottom edge of every first-run screen: a square way back, then the one
 * action, full width.
 *
 * Ported from `WelcomeScreen.kt` in
 * [Essentials](https://github.com/sameerasw/essentials), where every step ends
 * in exactly this row. The shape carries the meaning: the label sits hard left
 * and the glyph hard right, so the button reads as "what this does" on one end
 * and "where it takes you" on the other, and the two ends stay in the same place
 * on all four screens while everything above them changes.
 *
 * @param onBack `null` on the first step, which has nowhere to go back to. The
 *   square is then dropped rather than disabled — a dead control in the corner
 *   of the very first screen a user sees is worse than no control.
 * @param busy replaces the trailing glyph with a spinner rather than swapping
 *   the whole button, which would move the label out from under the finger.
 */
@Composable
internal fun OnboardingActions(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val view = rememberHapticView()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            OutlinedButton(
                onClick = {
                    LessonsHaptics.press(view)
                    onBack()
                },
                modifier = Modifier.size(ActionHeight),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Button(
            onClick = {
                LessonsHaptics.press(view)
                onClick()
            },
            enabled = enabled && !busy,
            modifier = Modifier
                .weight(1f)
                .height(ActionHeight),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.weight(1f))

            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * The big rounded headline every first-run step opens with.
 *
 * One composable rather than four copies of the same `Text` so that the four
 * screens cannot drift apart in size or weight, which is precisely what happened
 * to the two that existed before this.
 */
@Composable
internal fun OnboardingTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            // The rounded axis of the app's own face. Essentials reaches for it
            // on exactly these screens and nowhere else: a setup page is mostly
            // one sentence, and the sentence is the page.
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = GoogleSansFlexRounded,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The app's own launcher icon, on a plate, and you can spin it.
 *
 * A straight port of the logo on the Essentials welcome step, gesture and all:
 * dragging anywhere on the mark rotates it about its centre, a fine notch ticks
 * under the finger every couple of degrees, and letting go springs it back to
 * upright with a heavier tick each sixth of a turn. It does nothing. That is
 * the point — it is the first thing a user touches, it answers instantly, and it
 * says the app is going to feel like this.
 *
 * The plate is the launcher icon's own background colour rather than a theme
 * colour, because this *is* the icon they just tapped and the recognition is the
 * whole job of the screen. The foreground drawable is drawn untinted for the
 * same reason: tinting it would flatten three colours into one silhouette.
 */
@Composable
internal fun SpinnableAppMark(
    modifier: Modifier = Modifier,
    size: Dp = MarkSize,
) {
    val view = rememberHapticView()
    val scope = rememberCoroutineScope()
    val rotation = remember { Animatable(0f) }
    var centre by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(size)
            .onSizeChanged { centre = Offset(it.width / 2f, it.height / 2f) }
            .pointerInput(Unit) {
                var travelled = 0f
                var majorNotch = 0
                var minorNotch = 0

                detectDragGestures(
                    onDragStart = {
                        scope.launch { rotation.stop() }
                        travelled = rotation.value
                        majorNotch = round(travelled / MajorNotchDegrees).toInt()
                        minorNotch = round(travelled / MinorNotchDegrees).toInt()
                    },
                    onDrag = { change, _ ->
                        // The angle the finger swept about the centre, not the
                        // distance it moved: dragging in a circle spins the mark
                        // continuously, which a translation-to-rotation mapping
                        // cannot do.
                        val before = atan2(
                            change.previousPosition.y - centre.y,
                            change.previousPosition.x - centre.x,
                        )
                        val after = atan2(
                            change.position.y - centre.y,
                            change.position.x - centre.x,
                        )
                        var delta = (after - before) * DegreesPerRadian
                        // One sweep can never be half a turn; anything that
                        // looks like one is the branch cut of atan2 being
                        // crossed, so it is unwrapped rather than believed.
                        if (delta > HalfTurn) delta -= FullTurn
                        if (delta < -HalfTurn) delta += FullTurn
                        travelled += delta.toFloat()

                        val notch = round(travelled / MinorNotchDegrees).toInt()
                        if (notch != minorNotch) {
                            LessonsHaptics.tick(view)
                            minorNotch = notch
                        }
                        majorNotch = round(travelled / MajorNotchDegrees).toInt()

                        scope.launch { rotation.snapTo(travelled) }
                    },
                    onDragEnd = {
                        scope.launch {
                            rotation.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow,
                                ),
                            ) {
                                val notch = round(value / MajorNotchDegrees).toInt()
                                if (notch != majorNotch) {
                                    LessonsHaptics.tap(view)
                                    majorNotch = notch
                                }
                            }
                            travelled = 0f
                            majorNotch = 0
                            minorNotch = 0
                        }
                    },
                )
            }
            .graphicsLayer { rotationZ = rotation.value }
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(size * ForegroundScale),
        )
    }
}

/** How wide the mark is on the welcome step. */
private val MarkSize: Dp = 180.dp

/**
 * The foreground is drawn inside the 72 dp safe zone of a 108 dp adaptive
 * canvas, and the diary itself uses about three quarters of that, so the
 * drawable at plate size would sit in the middle of a lot of nothing. Scaled up
 * a quarter it covers roughly the two thirds of its plate that a launcher icon
 * covers of its own.
 */
private const val ForegroundScale = 1.25f

/** A fine notch under the finger while the mark is being dragged. */
private const val MinorNotchDegrees = 2f

/** A heavier one, every sixth of a turn, as it springs back. */
private const val MajorNotchDegrees = 60f

private const val DegreesPerRadian = 180.0 / PI
private const val HalfTurn = 180.0
private const val FullTurn = 360.0

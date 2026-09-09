package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.state.asPercent
import com.lumenpearson.lessons.core.designsystem.state.countdown
import com.lumenpearson.lessons.core.designsystem.state.formatShortRu
import com.lumenpearson.lessons.core.designsystem.state.progressOrNull
import com.lumenpearson.lessons.core.designsystem.state.visuals
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.DayState
import java.time.Duration
import java.time.LocalDateTime

/**
 * The one card that answers "what is happening right now".
 *
 * Everything else in the app is a list; this is the single element a pupil is
 * allowed to look at and then put the phone away, so it carries the state name,
 * the subject, the countdown and the progress of the current interval at four
 * clearly different type sizes.
 *
 * @param onClick optional; typically opens the full day. Omitted on the widget
 *   preview and anywhere the card is purely informational.
 */
@Composable
fun StateHeroCard(
    state: DayState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val visuals = state.visuals()
    val onContainer = contentColorFor(visuals.container)
        .takeOrElse { MaterialTheme.colorScheme.onSurface }
    val countdown = state.countdown
    val progress = state.progressOrNull

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = LessonsShapeTokens.HeroCard,
        color = visuals.container,
        contentColor = onContainer,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        // Expressive cookie shape instead of a circle — this is
                        // the one badge per screen that is allowed to be loud.
                        // If MaterialShapes moves, the fallback is
                        // LessonsShapeTokens.Badge (a plain circle).
                        .clip(MaterialShapes.Cookie6Sided.toShape())
                        .background(visuals.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = visuals.icon,
                        contentDescription = null,
                        tint = visuals.onAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // When there is no detail line the label is promoted to the
                    // headline, so "Каникулы" never renders twice.
                    if (visuals.detail != null) {
                        Text(
                            text = visuals.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = visuals.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = visuals.detail ?: visuals.label,
                        style = MaterialTheme.typography.headlineSmall,
                        color = onContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (countdown != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = countdown.formatShortRu(),
                        style = MaterialTheme.typography.displaySmall,
                        color = onContainer,
                        maxLines = 1,
                    )
                    Text(
                        text = when (state) {
                            is DayState.BeforeSchool -> stringResource(R.string.ds_countdown_until_start)
                            else -> stringResource(R.string.ds_countdown_until_end)
                        },
                        modifier = Modifier.padding(bottom = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
            }

            if (progress != null) {
                HeroProgress(
                    progress = progress,
                    accent = visuals.accent,
                    track = visuals.accent.copy(alpha = 0.22f),
                )
            }
        }
    }
}

/**
 * The wavy bar under the countdown.
 *
 * Separated out because it is the only stateful-looking part of the card and the
 * animation has to survive the card recomposing every minute; keeping it in its
 * own composable means only the bar re-runs when the fraction ticks.
 */
@Composable
private fun HeroProgress(
    progress: Float,
    accent: Color,
    track: Color,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        // Uses the MotionScheme installed by LessonsTheme, which is the point of
        // MaterialExpressiveTheme. If MaterialTheme.motionScheme moves, the
        // stable fallback is tween(durationMillis = 600).
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>(),
        label = "heroProgress",
    )
    val description = stringResource(R.string.ds_countdown_progress, progress.asPercent())

    // Expressive wavy indicator; if LinearWavyProgressIndicator is unavailable
    // the drop-in fallback is LinearProgressIndicator with the same arguments
    // minus the wave, so this stays a one-line swap.
    LinearWavyProgressIndicator(
        progress = { animated },
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .semantics { contentDescription = description },
        color = accent,
        trackColor = track,
    )
}

private val previewNow: LocalDateTime = LocalDateTime.of(2026, 9, 9, 9, 40)

@Preview(name = "Hero · идёт урок", showBackground = true)
@Composable
private fun StateHeroCardInLessonPreview() {
    LessonsTheme {
        StateHeroCard(
            state = DayState.InLesson(
                current = PreviewData.algebra,
                next = PreviewData.physics,
                endsIn = Duration.ofMinutes(30),
                progress = 0.33f,
                validUntil = previewNow.withHour(10).withMinute(10),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Hero · перемена", showBackground = true)
@Composable
private fun StateHeroCardOnBreakPreview() {
    LessonsTheme {
        StateHeroCard(
            state = DayState.OnBreak(
                previous = PreviewData.algebra,
                next = PreviewData.physics,
                endsIn = Duration.ofMinutes(9),
                progress = 0.4f,
                validUntil = previewNow.withHour(10).withMinute(25),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Hero · до уроков", showBackground = true)
@Composable
private fun StateHeroCardBeforeSchoolPreview() {
    LessonsTheme {
        StateHeroCard(
            state = DayState.BeforeSchool(
                next = PreviewData.russian,
                startsIn = Duration.ofMinutes(65),
                validUntil = previewNow.withHour(8).withMinute(30),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Hero · столовая", showBackground = true)
@Composable
private fun StateHeroCardEventPreview() {
    LessonsTheme {
        StateHeroCard(
            state = DayState.DuringEvent(
                event = PreviewData.canteen,
                next = PreviewData.history,
                endsIn = Duration.ofMinutes(11),
                progress = 0.25f,
                validUntil = previewNow.withHour(11).withMinute(25),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Hero · нет данных", showBackground = true)
@Composable
private fun StateHeroCardNoDataPreview() {
    LessonsTheme {
        StateHeroCard(
            state = DayState.NoData(date = previewNow.toLocalDate()),
            modifier = Modifier.padding(16.dp),
        )
    }
}

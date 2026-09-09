package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.Lesson
import java.time.LocalTime

private val GutterWidth = 26.dp
private val NodeSize = 10.dp
private val RailStroke = 2.dp

/**
 * The day as a vertical rail of [LessonRow]s with the current moment marked.
 *
 * A plain list answers "what are my lessons"; the rail answers "where am I in
 * the day", which is the question a pupil actually opens the app with. The "now"
 * marker is what makes a half-finished day legible at a glance.
 *
 * @param now current wall-clock time, or `null` when the timeline is showing a
 *   different date and no marker should be drawn at all.
 * @param showTeacher forwarded to every [LessonRow]; see its documentation.
 */
@Composable
fun LessonTimeline(
    lessons: List<Lesson>,
    modifier: Modifier = Modifier,
    now: LocalTime? = null,
    showTeacher: Boolean = true,
    onLessonClick: ((Lesson) -> Unit)? = null,
) {
    if (lessons.isEmpty()) return

    val scheme = MaterialTheme.colorScheme
    val ordered = remember(lessons) { lessons.sortedBy { it.startsAt } }

    // Index of the lesson containing `now`, or -1. When a lesson is running the
    // row itself carries the highlight and a separate marker would be noise.
    val currentIndex = remember(ordered, now) {
        if (now == null) -1 else ordered.indexOfFirst { now >= it.startsAt && now < it.endsAt }
    }

    // Otherwise the marker sits *between* rows: before the lesson that has not
    // started yet, or after the last row once the day is done.
    val markerIndex = remember(ordered, now, currentIndex) {
        when {
            now == null || currentIndex >= 0 -> -1
            else -> ordered.indexOfFirst { now < it.startsAt }.takeIf { it >= 0 } ?: ordered.size
        }
    }

    val railColor = scheme.outlineVariant
    val gutterCenter = GutterWidth / 2

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = gutterCenter.toPx()
                drawLine(
                    // Fading both ends keeps the rail from looking like it was
                    // cut off by the edge of the screen.
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.08f to railColor,
                        0.92f to railColor,
                        1f to Color.Transparent,
                    ),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = RailStroke.toPx(),
                    cap = StrokeCap.Round,
                )
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ordered.forEachIndexed { index, lesson ->
            if (index == markerIndex) NowMarker(accent = scheme.primary)

            val isCurrent = index == currentIndex
            val isPast = now != null && now >= lesson.endsAt

            Row(verticalAlignment = Alignment.CenterVertically) {
                TimelineNode(
                    color = when {
                        isCurrent -> scheme.primary
                        isPast -> scheme.outlineVariant
                        else -> scheme.outline
                    },
                    isCurrent = isCurrent,
                )
                LessonRow(
                    lesson = lesson,
                    modifier = Modifier.weight(1f),
                    isCurrent = isCurrent,
                    showTeacher = showTeacher,
                    onClick = onLessonClick?.let { click -> { click(lesson) } },
                )
            }
        }

        if (markerIndex == ordered.size) NowMarker(accent = scheme.primary)
    }
}

/** One dot on the rail; the ring on the current lesson is what draws the eye down the column. */
@Composable
private fun TimelineNode(color: Color, isCurrent: Boolean) {
    Box(
        modifier = Modifier.width(GutterWidth),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isCurrent) NodeSize + 4.dp else NodeSize)
                .clip(LessonsShapeTokens.Badge)
                .background(color)
                .then(
                    if (isCurrent) {
                        Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = LessonsShapeTokens.Badge,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/** The "сейчас" line, drawn only in the gaps between lessons. */
@Composable
private fun NowMarker(accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(GutterWidth),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(LessonsShapeTokens.Badge)
                    .background(accent),
            )
        }
        PillChip(
            text = stringResource(R.string.ds_lesson_now),
            containerColor = accent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
                .height(RailStroke)
                .background(accent.copy(alpha = 0.35f)),
        )
    }
}

@Preview(name = "LessonTimeline · середина дня", showBackground = true, heightDp = 620)
@Composable
private fun LessonTimelinePreview() {
    LessonsTheme {
        LessonTimeline(
            lessons = PreviewData.day,
            modifier = Modifier.padding(12.dp),
            now = LocalTime.of(9, 40),
        )
    }
}

@Preview(name = "LessonTimeline · перемена", showBackground = true, heightDp = 620)
@Composable
private fun LessonTimelineBreakPreview() {
    LessonsTheme {
        LessonTimeline(
            lessons = PreviewData.day,
            modifier = Modifier.padding(12.dp),
            now = LocalTime.of(10, 18),
        )
    }
}

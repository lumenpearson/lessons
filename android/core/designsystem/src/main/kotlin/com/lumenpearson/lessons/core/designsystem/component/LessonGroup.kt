package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.Lesson
import java.time.LocalTime

/**
 * A day as one group of lesson rows, with the current moment marked.
 *
 * A plain list answers "what are my lessons"; the marker answers "where am I in
 * the day", which is the question a pupil actually opens the app with. While a
 * lesson is running the row itself carries the tint, so the separator is drawn
 * only during a break — two markers for one moment is noise.
 *
 * @param now current wall-clock time, or `null` when the group is showing a
 *   different date and nothing should be marked at all.
 */
@Composable
fun LessonGroup(
    lessons: List<Lesson>,
    modifier: Modifier = Modifier,
    now: LocalTime? = null,
    showTeacher: Boolean = true,
    onLessonClick: ((Lesson) -> Unit)? = null,
) {
    if (lessons.isEmpty()) return

    val ordered = remember(lessons) { lessons.sortedBy { it.startsAt } }

    val currentIndex = remember(ordered, now) {
        if (now == null) -1 else ordered.indexOfFirst { now >= it.startsAt && now < it.endsAt }
    }

    // Between rows: before the lesson that has not started yet, or after the last
    // row once the day is done.
    val markerIndex = remember(ordered, now, currentIndex) {
        when {
            now == null || currentIndex >= 0 -> -1
            else -> ordered.indexOfFirst { now < it.startsAt }.takeIf { it >= 0 } ?: ordered.size
        }
    }

    RoundedCardContainer(modifier = modifier) {
        ordered.forEachIndexed { index, lesson ->
            if (index == markerIndex) NowSeparator()
            LessonRow(
                lesson = lesson,
                isCurrent = index == currentIndex,
                showTeacher = showTeacher,
                onClick = onLessonClick?.let { click -> { click(lesson) } },
            )
        }
        if (markerIndex == ordered.size) NowSeparator()
    }
}

/** The "сейчас" line, drawn only in the gaps between lessons. */
@Composable
private fun NowSeparator(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(LessonsShapeTokens.Tile)
                .background(accent),
        )
        PillChip(
            text = stringResource(R.string.ds_lesson_now),
            containerColor = accent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .clip(LessonsShapeTokens.Pill)
                .background(accent.copy(alpha = 0.3f)),
        )
    }
}

@Preview(name = "LessonGroup · середина дня", showBackground = true, heightDp = 520)
@Composable
private fun LessonGroupPreview() {
    LessonsTheme {
        LessonGroup(
            lessons = PreviewData.day,
            modifier = Modifier.padding(16.dp),
            now = LocalTime.of(9, 40),
        )
    }
}

@Preview(name = "LessonGroup · перемена", showBackground = true, heightDp = 520)
@Composable
private fun LessonGroupBreakPreview() {
    LessonsTheme {
        LessonGroup(
            lessons = PreviewData.day,
            modifier = Modifier.padding(16.dp),
            now = LocalTime.of(10, 18),
        )
    }
}

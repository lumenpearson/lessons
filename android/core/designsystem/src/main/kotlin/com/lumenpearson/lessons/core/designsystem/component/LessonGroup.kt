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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.Lesson
import java.time.LocalTime

/**
 * A day as one group of lesson rows, with the current moment marked.
 *
 * A plain list answers "what are my lessons"; the marker answers "where am I in
 * the day", which is the question a pupil actually opens the app with. The
 * «сейчас» separator is drawn wherever the moment is: above the lesson that is
 * running, whose row is also painted with its subject's gradient, or in the gap
 * a break leaves between two rows. It used to be drawn during breaks only, with
 * a «сейчас» chip inside the running row instead; the owner asked on 2 October
 * 2026 for one mark in both cases, so that the trailing end of the row is free
 * for the diary's marks.
 *
 * @param now current wall-clock time, or `null` when the group is showing a
 *   different date and nothing should be marked at all.
 * @param grades the diary's marks by lesson; a lesson missing from the map has
 *   none. See `gradesByLesson` in `:app` for how a mark finds its lesson.
 */
@Composable
fun LessonGroup(
    lessons: List<Lesson>,
    modifier: Modifier = Modifier,
    now: LocalTime? = null,
    showTeacher: Boolean = true,
    grades: Map<Lesson, List<String>> = emptyMap(),
    onLessonClick: ((Lesson) -> Unit)? = null,
) {
    if (lessons.isEmpty()) return

    val ordered = remember(lessons) { lessons.sortedBy { it.startsAt } }
    val marker = remember(ordered, now) { nowMarkerOf(ordered, now) }

    RoundedCardContainer(modifier = modifier) {
        ordered.forEachIndexed { index, lesson ->
            if (index == marker.separatorBefore) NowSeparator()
            LessonRow(
                lesson = lesson,
                isCurrent = index == marker.currentIndex,
                showTeacher = showTeacher,
                grades = grades[lesson].orEmpty(),
                onClick = onLessonClick?.let { click -> { click(lesson) } },
            )
        }
        if (marker.separatorBefore == ordered.size) NowSeparator()
    }
}

/**
 * Where [LessonGroup] puts the moment.
 *
 * @property currentIndex the running lesson's row, or -1.
 * @property separatorBefore the row the «сейчас» line goes above — the running
 *   lesson, or the next one during a break; the list's size once the day is
 *   done; -1 when nothing is marked.
 */
data class NowMarker(val currentIndex: Int, val separatorBefore: Int)

/** [NowMarker] for [ordered], sorted by start; pure, so a JVM test can ask it. */
fun nowMarkerOf(ordered: List<Lesson>, now: LocalTime?): NowMarker {
    val current = if (now == null) -1 else ordered.indexOfFirst { now >= it.startsAt && now < it.endsAt }
    val next = if (now == null) -1 else ordered.indexOfFirst { now < it.startsAt }
    return when {
        now == null || ordered.isEmpty() -> NowMarker(currentIndex = -1, separatorBefore = -1)
        current >= 0 -> NowMarker(currentIndex = current, separatorBefore = current)
        else -> NowMarker(currentIndex = -1, separatorBefore = if (next >= 0) next else ordered.size)
    }
}

/** The «сейчас» line: above the running lesson, or in the gap a break leaves. */
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
            text = correctedString(R.string.ds_lesson_now),
            selected = true,
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

package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.state.formatTimeRange
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import com.lumenpearson.lessons.core.model.Lesson

/**
 * One line of the timetable, shaped like every other row in the app.
 *
 * The lesson number lives inside the subject's own colour tile, so a column of
 * rows can be scanned by hue before a single word is read. The three deviations
 * a school day throws at a pupil — running now, swapped, cancelled — each get a
 * different *kind* of signal (tinted row, chip, strikethrough) rather than three
 * shades of the same one.
 *
 * @param isCurrent the lesson containing the current wall clock; tints the row.
 * @param showTeacher bound to a user setting: pupils who have had the same five
 *   teachers for years read the row faster without the name in it.
 */
@Composable
fun LessonRow(
    lesson: Lesson,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    showTeacher: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val tone = if (lesson.isCancelled) neutralTone() else subjectTone(lesson.subject, lesson.colorHex)
    val indexDescription = stringResource(R.string.ds_lesson_index, lesson.index)

    GroupRow(
        modifier = modifier,
        // The running lesson borrows its own subject tint instead of a generic
        // highlight, which keeps the row inside the palette it already had.
        container = if (isCurrent) tone.container else scheme.rowContainer,
        onClick = onClick,
    ) {
        AccentTile(tone = tone) {
            Text(
                text = lesson.index.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = tone.content,
                modifier = Modifier.clearAndSetSemantics { contentDescription = indexDescription },
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = lesson.subject,
                // The running lesson is the one bold row of the day. Its
                // neighbours keep the scale's Medium rather than dropping to
                // Normal, so they still match every other row title in the app.
                style = MaterialTheme.typography.titleMedium
                    .emphasised(isCurrent, resting = FontWeight.Medium),
                color = if (lesson.isCancelled) scheme.onSurfaceVariant else scheme.onSurface,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = lesson.metaLine(showTeacher = showTeacher),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = lesson.note
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = tone.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            lesson.isCancelled -> PillChip(
                text = stringResource(R.string.ds_lesson_cancelled),
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
            )

            isCurrent -> PillChip(
                text = stringResource(R.string.ds_lesson_now),
                selected = true,
                containerColor = tone.content,
                contentColor = tone.container,
            )

            lesson.isReplaced -> PillChip(
                text = stringResource(R.string.ds_lesson_replaced),
                containerColor = scheme.tertiaryContainer,
                contentColor = scheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * "08:30 – 09:15 · каб. 212 · Соколова А. В." with the absent parts dropped.
 *
 * Built as one string rather than three composables because room and teacher are
 * optional in the wire format and three conditionally-visible Texts produce a
 * ragged row on the days when only some of them arrive.
 */
@Composable
private fun Lesson.metaLine(showTeacher: Boolean): String {
    val parts = buildList {
        add(formatTimeRange(startsAt, endsAt))
        room?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.ds_lesson_room, it)) }
        if (showTeacher) teacher?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.joinToString(separator = " · ")
}

@Preview(name = "LessonRow", showBackground = true)
@Composable
private fun LessonRowPreview() {
    LessonsTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RoundedCardContainer {
                LessonRow(lesson = PreviewData.russian)
                LessonRow(lesson = PreviewData.algebra, isCurrent = true)
                LessonRow(lesson = PreviewData.physics)
                LessonRow(lesson = PreviewData.history, showTeacher = false)
            }
        }
    }
}

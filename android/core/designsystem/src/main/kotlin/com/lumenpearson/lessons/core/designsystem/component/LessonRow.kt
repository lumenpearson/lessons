package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.state.formatTimeRange
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.parseSubjectColor
import com.lumenpearson.lessons.core.model.Lesson

/**
 * One line of the timetable.
 *
 * All three deviations a school day can throw at a pupil — this is the lesson
 * running now, this one was swapped, this one is off — have to be readable in
 * the half-second it takes to scroll past, so each gets a different *kind* of
 * signal (fill, outline, strikethrough) rather than three shades of the same one.
 *
 * @param isCurrent the lesson containing the current wall clock; fills the row.
 * @param showTeacher bound to a user setting: pupils who have had the same five
 *   teachers for years read the row faster without the name in it.
 * @param onClick optional; the row stays a plain surface when it is `null` so a
 *   read-only timetable does not offer a ripple that leads nowhere.
 */
@Composable
fun LessonRow(
    lesson: Lesson,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    showTeacher: Boolean = true,
    shape: Shape = LessonsShapeTokens.ListRow,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val subjectColor = parseSubjectColor(lesson.colorHex)

    val container = when {
        isCurrent -> scheme.primaryContainer
        lesson.isCancelled -> scheme.surfaceContainerLow
        else -> scheme.surfaceContainer
    }
    val onContainer = when {
        isCurrent -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    // A замена keeps the neutral fill but gains an amber outline: it is still a
    // lesson you have to attend, it just is not the one on the printed schedule.
    val border = when {
        lesson.isReplaced && !lesson.isCancelled -> BorderStroke(1.5.dp, scheme.tertiary)
        else -> null
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = container,
        contentColor = onContainer,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LessonIndexBadge(
                index = lesson.index,
                isCurrent = isCurrent,
                isCancelled = lesson.isCancelled,
                subjectColor = subjectColor,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = lesson.subject,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (lesson.isCancelled) scheme.onSurfaceVariant else onContainer,
                        textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (lesson.isCancelled) {
                        PillChip(
                            text = stringResource(R.string.ds_lesson_cancelled),
                            containerColor = scheme.errorContainer,
                            contentColor = scheme.onErrorContainer,
                        )
                    } else if (lesson.isReplaced) {
                        PillChip(
                            text = stringResource(R.string.ds_lesson_replaced),
                            icon = Icons.Rounded.SwapHoriz,
                            containerColor = scheme.tertiaryContainer,
                            contentColor = scheme.onTertiaryContainer,
                        )
                    }
                }

                Text(
                    text = lesson.metaLine(showTeacher = showTeacher),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) onContainer else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val note = lesson.note
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.tertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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

/** The lesson number, kept as a fixed-width circle so rows stay optically aligned. */
@Composable
private fun LessonIndexBadge(
    index: Int,
    isCurrent: Boolean,
    isCancelled: Boolean,
    subjectColor: Color?,
) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        isCurrent -> scheme.primary
        isCancelled -> scheme.surfaceContainerHigh
        // The school's own subject colour, when it sent one, is the only place
        // in the row where arbitrary colour is allowed in.
        subjectColor != null -> subjectColor
        else -> scheme.secondaryContainer
    }
    val foreground = when {
        isCurrent -> scheme.onPrimary
        isCancelled -> scheme.onSurfaceVariant
        subjectColor != null -> Color.White
        else -> scheme.onSecondaryContainer
    }
    // A bare digit read aloud as "2" is meaningless, so the badge replaces its
    // own contents with the spelled-out "Урок 2" for accessibility services.
    val indexDescription = stringResource(R.string.ds_lesson_index, index)

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(LessonsShapeTokens.Badge)
            .background(background)
            .clearAndSetSemantics { contentDescription = indexDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
        )
    }
}

@Preview(name = "LessonRow", showBackground = true)
@Composable
private fun LessonRowPreview() {
    LessonsTheme {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LessonRow(lesson = PreviewData.russian)
            LessonRow(lesson = PreviewData.algebra, isCurrent = true)
            LessonRow(lesson = PreviewData.physics)
            LessonRow(lesson = PreviewData.history, showTeacher = false)
        }
    }
}

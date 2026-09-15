package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import com.lumenpearson.lessons.core.model.HomeworkItem

/**
 * One subject's homework, as a row of the same family as the timetable's.
 *
 * Homework is the only long-form text in the app, so this row never truncates
 * it: a pupil who has to tap to read the assignment is a pupil who copies it
 * down wrong. That is also why the tile is top-aligned rather than centred — on
 * a five-line assignment a centred tile floats in the middle of nowhere.
 *
 * @param onOpenAttachment invoked with the attachment URL; when `null` the
 *   attachment is still announced but not offered as a tap target.
 */
@Composable
fun HomeworkRow(
    item: HomeworkItem,
    modifier: Modifier = Modifier,
    tone: AccentTone = subjectTone(item.subject),
    onOpenAttachment: ((String) -> Unit)? = null,
    /**
     * Opens the row. Null everywhere the row is only something to read, which
     * is still most places; [LessonRow] has carried the same parameter for the
     * same reason since the week screen gained its sheet.
     */
    onClick: (() -> Unit)? = null,
    /**
     * A short label under the text, for something true about the row rather
     * than part of it — «Исправлено», today.
     *
     * A slot of its own rather than something a caller appends to [item]: the
     * subject is what [tone] is derived from, so a word added to it recolours
     * the row away from every other card of that subject, and it is drawn on
     * one ellipsised line, so on a long subject name the label is the half that
     * disappears.
     */
    badge: String? = null,
) {
    val scheme = MaterialTheme.colorScheme

    GroupRow(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        onClick = onClick,
    ) {
        AccentIconTile(icon = Icons.Rounded.EditNote, tone = tone)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.subject,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )

            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            val attachment = item.attachmentUrl
            if (attachment != null) {
                Row(
                    modifier = Modifier
                        .clip(LessonsShapeTokens.Pill)
                        .then(
                            if (onOpenAttachment != null) {
                                Modifier.clickable { onOpenAttachment(attachment) }
                            } else {
                                Modifier
                            },
                        )
                        .background(tone.container)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AttachFile,
                        contentDescription = null,
                        tint = tone.content,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.ds_homework_attachment),
                        style = MaterialTheme.typography.labelLarge,
                        color = tone.content,
                    )
                }
            }
        }
    }
}

@Preview(name = "HomeworkRow", showBackground = true)
@Composable
private fun HomeworkRowPreview() {
    LessonsTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(title = stringResource(R.string.ds_homework_title))
            RoundedCardContainer {
                HomeworkRow(item = PreviewData.homework, onOpenAttachment = {})
                HomeworkRow(item = PreviewData.homeworkWithoutAttachment)
            }
        }
    }
}

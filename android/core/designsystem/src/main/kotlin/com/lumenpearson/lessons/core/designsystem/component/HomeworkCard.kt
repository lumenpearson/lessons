package com.lumenpearson.lessons.core.designsystem.component

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
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.HomeworkItem

/**
 * One subject's homework.
 *
 * Homework is the only long-form text in the app, so this card gives it a real
 * measure and never truncates it: a pupil who has to tap to read the assignment
 * is a pupil who copies it down wrong.
 *
 * @param accentColor the subject's own colour when the caller has the matching
 *   [com.lumenpearson.lessons.core.model.Lesson] at hand — homework carries only
 *   a subject name, so the colour has to be handed in from outside.
 * @param onOpenAttachment invoked with the attachment URL; when `null` the
 *   attachment is still announced but not offered as a tap target, which is what
 *   the widget needs since it cannot open a browser itself.
 */
@Composable
fun HomeworkCard(
    item: HomeworkItem,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onOpenAttachment: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = scheme.surfaceContainer,
        contentColor = scheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // A colour tick rather than an icon: the subject name is already
                // the label, and a stack of these cards scans faster by colour.
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 18.dp)
                        .clip(LessonsShapeTokens.Pill)
                        .background(accentColor),
                )
                Text(
                    text = item.subject,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )

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
                        .background(scheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AttachFile,
                        contentDescription = null,
                        tint = scheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.ds_homework_attachment),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Preview(name = "HomeworkCard", showBackground = true)
@Composable
private fun HomeworkCardPreview() {
    LessonsTheme {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(title = stringResource(R.string.ds_homework_title))
            HomeworkCard(
                item = PreviewData.homework,
                accentColor = MaterialTheme.colorScheme.secondary,
                onOpenAttachment = {},
            )
            HomeworkCard(item = PreviewData.homeworkWithoutAttachment)
        }
    }
}

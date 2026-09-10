package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

/**
 * The one shape every "there is nothing here" moment takes.
 *
 * A school diary is empty far more often than a social app is — weekends,
 * holidays, a class with no homework — so the empty case takes the same rounded
 * card as a full one rather than leaving a hole in the stack of groups.
 *
 * @param icon defaults to the "nothing scheduled" glyph, which is what the
 *   overwhelming majority of empty states in this app actually mean.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.EventBusy,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LessonsShapeTokens.Group,
        color = MaterialTheme.colorScheme.rowContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentIconTile(
                icon = icon,
                tone = accentTone(3),
                size = 64.dp,
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (actionLabel != null && onActionClick != null) {
                FilledTonalButton(
                    onClick = onActionClick,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Preview(name = "EmptyState", showBackground = true)
@Composable
private fun EmptyStatePreview() {
    LessonsTheme {
        EmptyState(
            title = "Уроков нет",
            description = "На эту дату расписание пустое. Как только школа его опубликует, оно появится здесь.",
            modifier = Modifier.padding(16.dp),
            actionLabel = "Обновить",
            onActionClick = {},
        )
    }
}

package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.designsystem.component.LessonsLoadingIndicator
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.ui.admin.ManagementSheet
import com.lumenpearson.lessons.ui.admin.SheetField
import com.lumenpearson.lessons.ui.admin.SheetNote
import com.lumenpearson.lessons.ui.admin.SheetSection

/**
 * Correcting one row of the diary.
 *
 * The whole row at once, because that is how a person thinks about it — "this
 * lesson is in 204, not 12" — and because the alternative, a sheet per field,
 * would put four taps between somebody and a lesson whose room *and* teacher
 * are both wrong. The view model turns the difference between what was typed
 * and what the diary says into a correction per field, or into a reset when a
 * field is typed back to the diary's own answer.
 *
 * Under every field is what the diary itself says. That is the part that makes
 * this honest rather than a way to rewrite a record: the school's answer is
 * never replaced on screen, only covered, and it stays one line below where it
 * was covered.
 */
@Composable
internal fun DiaryEditSheet(
    corrections: DiaryCorrections,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Map<DiaryField, String>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the row, so opening a different lesson starts from that lesson's
    // values rather than from whatever was typed into the last one.
    val typed = remember(corrections.target) {
        mutableStateMapOf<DiaryField, String>().apply { putAll(corrections.values) }
    }

    ManagementSheet(
        // The action, not the subject: the row is named in the first line
        // below, where there is room for the lesson number too. Two halves of a
        // double lesson are the same subject, so a sheet titled with it alone
        // cannot say which one is being corrected.
        title = correctedString(R.string.diary_edit_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        SheetSection(
            title = corrections.subtitle
                ?.let { correctedString(R.string.diary_edit_which, corrections.title, it) }
                ?: corrections.title,
        )
        SheetNote(text = correctedString(R.string.diary_edit_message))

        if (corrections.ambiguous) {
            SheetNote(text = correctedString(R.string.diary_edit_ambiguous))
        } else {
            corrections.fields.forEach { field ->
                SheetField(
                    value = typed[field].orEmpty(),
                    onValueChange = { typed[field] = it },
                    label = correctedString(field.labelRes),
                    singleLine = field != DiaryField.HOMEWORK && field != DiaryField.TEXT,
                    minLines = if (field == DiaryField.HOMEWORK || field == DiaryField.TEXT) 2 else 1,
                    enabled = !saving,
                )
                SheetNote(
                    text = corrections.upstreamOf(field)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { correctedString(R.string.diary_edit_upstream, it) }
                        ?: correctedString(R.string.diary_edit_upstream_empty),
                )
                if (field in corrections.changedUpstream) {
                    SheetNote(text = correctedString(R.string.diary_edit_changed))
                }
            }
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only offered when there is something to take off. A reset button
            // on a row nobody has corrected is a button that does nothing, and
            // next to fields holding the diary's own values it reads like a way
            // to clear them.
            if (corrections.hasCorrections) {
                TextButton(onClick = onReset, enabled = !saving) {
                    Text(
                        text = correctedString(R.string.diary_edit_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(text = correctedString(R.string.action_cancel))
                }
                if (!corrections.ambiguous) {
                    Button(onClick = { onSave(typed.toMap()) }, enabled = !saving) {
                        if (saving) {
                            LessonsLoadingIndicator()
                        } else {
                            Text(text = correctedString(R.string.diary_edit_save))
                        }
                    }
                }
            }
        }
    }
}

/** What each correctable field is called on screen. */
private val DiaryField.labelRes: Int
    get() = when (this) {
        DiaryField.HOMEWORK -> R.string.diary_edit_field_homework
        DiaryField.ROOM -> R.string.diary_edit_field_room
        DiaryField.TEACHER -> R.string.diary_edit_field_teacher
        DiaryField.TOPIC -> R.string.diary_edit_field_topic
        DiaryField.TEXT -> R.string.diary_edit_field_text
    }

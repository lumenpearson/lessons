package com.lumenpearson.lessons.ui.admin

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import kotlinx.coroutines.launch

/**
 * «📤 Экспорт» and «📥 Импорт»: the whole weekly template as one text.
 *
 * The same format in both directions is what makes it a backup — a text saved
 * out of the bot goes back in here, and the other way round — so the export is
 * shown verbatim and copies with one tap rather than being re-rendered into
 * something prettier that would no longer parse.
 *
 * The import never overwrites silently. A paste that would replace a weekday
 * that already has lessons is answered by the server with the conflicts and
 * nothing written; this sheet shows what would go, and «Заменить» sends the
 * same text again with consent. That is the bot's «Применить», with the preview
 * turned into a refusal because an API has no screen to draw one on.
 */
@Composable
fun TimetableSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var paste by rememberSaveable { mutableStateOf("") }
    var copied by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = correctedString(R.string.admin_clipboard_label)
    val export = state.timetable.value
    val pending = state.pendingImport
    val problem = remember(paste) { if (paste.isEmpty()) null else pasteProblem(paste) }

    ManagementSheet(
        title = correctedString(R.string.admin_timetable_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        SheetNotice(
            text = state.notice?.takeIf { it is ManagementNotice.Imported }?.asText()
                ?: correctedString(R.string.admin_timetable_copied).takeIf { copied },
        )

        SheetSection(title = correctedString(R.string.admin_timetable_export))
        when {
            export == null && state.timetable.loading -> SkeletonGroup(
                modifier = Modifier.padding(horizontal = ScreenPadding),
                rows = 3,
            )

            export == null -> ManagementFailureCard(
                failure = state.timetable.failure,
                modifier = Modifier.padding(horizontal = ScreenPadding),
                onRetry = viewModel::loadTimetable,
            )

            export.text.isBlank() -> EmptyState(
                title = correctedString(R.string.admin_timetable_export_empty),
                description = correctedString(R.string.admin_timetable_paste_note),
                icon = Icons.Rounded.CalendarViewWeek,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )

            else -> {
                SheetNote(text = correctedString(R.string.admin_timetable_lessons, export.lessons))
                ExportBlock(text = export.text)
                GroupActionItem(
                    label = correctedString(R.string.admin_timetable_copy),
                    icon = Icons.Rounded.ContentCopy,
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipData.newPlainText(clipLabel, export.text).toClipEntry(),
                            )
                            copied = true
                        }
                    },
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }

        SheetSection(title = correctedString(R.string.admin_timetable_import))
        SheetNote(text = correctedString(R.string.admin_timetable_paste_note))
        SheetField(
            value = paste,
            onValueChange = {
                paste = it
                copied = false
            },
            label = correctedString(R.string.admin_timetable_paste_label),
            singleLine = false,
            minLines = 4,
            enabled = !state.working && pending == null,
        )
        SheetProblem(problem = problem)
        SheetFailure(failure = state.writeFailure)

        // Shown after either outcome: the lines the parser could not read are
        // the same two typos whether or not the rest of the paste went in.
        if (state.importRejected.isNotEmpty()) {
            SheetSection(title = correctedString(R.string.admin_timetable_rejected_title))
            RejectedLines(state.importRejected)
        }

        if (pending == null) {
            SheetButtons(
                confirmLabel = correctedString(R.string.admin_timetable_apply),
                onConfirm = { viewModel.importTimetable(paste) },
                onCancel = onDismiss,
                enabled = paste.isNotBlank() && problem == null,
                busy = state.working,
            )
        } else {
            ImportConflicts(
                pending = pending,
                busy = state.working,
                onReplace = {
                    viewModel.replacePendingImport()
                    paste = ""
                },
                onCancel = viewModel::cancelPendingImport,
            )
        }
    }
}

/**
 * What an import would overwrite, and the two answers to it.
 *
 * Cancel is the safe one and is the plain button; «Заменить» is drawn as
 * destructive, because it is — the lessons counted in [ImportConflictSummary]
 * are gone the moment it is pressed, on days the admin may not be looking at.
 */
@Composable
private fun ImportConflicts(
    pending: PendingImport,
    busy: Boolean,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    val summary = remember(pending.conflicts) { summariseImportConflicts(pending.conflicts) }

    SheetSection(title = correctedString(R.string.admin_timetable_conflicts_title))
    SheetNote(
        text = correctedString(
            R.string.admin_timetable_conflicts_summary,
            summary.days,
            summary.existing,
            summary.incoming,
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        summary.lines.forEach { line ->
            val day = line.weekday?.asWeekdayName()
                ?: correctedString(R.string.admin_timetable_weekday_unknown, line.number)
            Text(
                text = correctedString(
                    R.string.admin_timetable_conflict_line,
                    day,
                    line.existing,
                    line.incoming,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    SheetNote(text = correctedString(R.string.admin_timetable_conflicts_note))

    SheetButtons(
        confirmLabel = correctedString(R.string.admin_timetable_replace),
        onConfirm = onReplace,
        onCancel = onCancel,
        busy = busy,
        destructive = true,
    )
}

/** The lines the parser could not read, echoed so two typos can be fixed. */
@Composable
private fun RejectedLines(lines: List<String>) {
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The export text as it will be pasted, on the group's own row colour. */
@Composable
private fun ExportBlock(text: String) {
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

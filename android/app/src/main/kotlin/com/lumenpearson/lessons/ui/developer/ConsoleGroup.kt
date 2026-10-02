package com.lumenpearson.lessons.ui.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.developer.ConsoleAnswer
import com.lumenpearson.lessons.core.data.developer.ConsoleAuth
import com.lumenpearson.lessons.core.data.developer.ConsoleDraft
import com.lumenpearson.lessons.core.data.developer.ConsoleMethod
import com.lumenpearson.lessons.core.data.developer.ConsoleOutcome
import com.lumenpearson.lessons.core.data.developer.ConsolePresets
import com.lumenpearson.lessons.core.data.developer.ConsoleRefusal
import com.lumenpearson.lessons.core.data.developer.ConsoleTarget
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.text.correctedLine
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

/** The first status that is not a success; a 3xx is an answer the console shows, not a failure. */
private const val FirstErrorStatus = 400

/**
 * The request console (#237): one request at a time, to our server or a diary
 * the catalog allow-lists, with the answer underneath.
 *
 * The bearers are chosen, never typed or shown: «Токен устройства» and
 * «Токен дневника» are read when the request leaves. A diary gets no bearer of
 * ours — the choice is not offered there, and the planner refuses it anyway.
 */
@Composable
internal fun ConsoleGroup(
    draft: ConsoleDraft,
    origins: List<String>,
    state: ConsoleState,
    onEdit: ((ConsoleDraft) -> ConsoleDraft) -> Unit,
    onSend: () -> Unit,
    onCopy: (String) -> Unit,
) {
    DeveloperGroup(
        title = correctedString(R.string.developer_console_group),
        subtitle = correctedString(R.string.developer_console_summary),
    ) {
        GroupItem(
            title = correctedString(R.string.developer_console_presets),
            icon = Icons.Rounded.Bookmarks,
            tone = neutralTone(),
            trailing = {
                Picker(
                    items = ConsolePresets,
                    label = { "${it.method} ${it.path}" },
                    onPick = { preset -> onEdit { preset.copy(headers = it.headers) } },
                )
            },
        )
        GroupSegmentedItem(
            title = correctedString(R.string.developer_console_target),
            tone = accentTone(2),
            items = ConsoleTarget.entries,
            selectedItem = draft.target,
            onItemSelected = { target ->
                onEdit {
                    it.copy(
                        target = target,
                        origin = it.origin ?: origins.firstOrNull(),
                        auth = if (target == ConsoleTarget.DIARY) ConsoleAuth.NONE else it.auth,
                    )
                }
            },
            labelProvider = { target ->
                correctedString(
                    when (target) {
                        ConsoleTarget.SERVER -> R.string.developer_console_target_server
                        ConsoleTarget.DIARY -> R.string.developer_console_target_diary
                    },
                )
            },
            icon = Icons.Rounded.Dns,
        )
        if (draft.target == ConsoleTarget.DIARY) {
            GroupItem(
                title = draft.origin ?: correctedString(R.string.developer_console_no_origin),
                subtitle = correctedString(R.string.developer_console_origin),
                tone = neutralTone(),
                trailing = {
                    Picker(items = origins, label = { it }, onPick = { origin -> onEdit { it.copy(origin = origin) } })
                },
            )
        }
        GroupItem(
            title = draft.method.name,
            subtitle = correctedString(R.string.developer_console_method),
            icon = Icons.Rounded.Http,
            tone = neutralTone(),
            trailing = {
                Picker(
                    items = ConsoleMethod.entries,
                    label = { it.name },
                    onPick = { method -> onEdit { it.copy(method = method) } },
                )
            },
        )
        if (draft.target == ConsoleTarget.SERVER) {
            GroupSegmentedItem(
                title = correctedString(R.string.developer_console_auth),
                tone = accentTone(1),
                items = ConsoleAuth.entries,
                selectedItem = draft.auth,
                onItemSelected = { auth -> onEdit { it.copy(auth = auth) } },
                labelProvider = { auth -> authLabel(auth) },
                icon = Icons.Rounded.Key,
            )
        }
        ConsoleFields(draft = draft, onEdit = onEdit)
        GroupActionItem(
            label = correctedString(R.string.developer_console_send),
            icon = Icons.AutoMirrored.Rounded.Send,
            enabled = state != ConsoleState.Sending,
            busy = state == ConsoleState.Sending,
            onClick = onSend,
        )
        if (state is ConsoleState.Done) ConsoleResult(outcome = state.outcome, onCopy = onCopy)
    }
}

@Composable
private fun authLabel(auth: ConsoleAuth): String = correctedString(
    when (auth) {
        ConsoleAuth.NONE -> R.string.developer_console_auth_none
        ConsoleAuth.DEVICE -> R.string.developer_console_auth_device
        ConsoleAuth.DIARY -> R.string.developer_console_auth_diary
    },
)

/** The path, the headers and — under a method that sends one — the body, in a monospaced face. */
@Composable
private fun ConsoleFields(draft: ConsoleDraft, onEdit: ((ConsoleDraft) -> ConsoleDraft) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.rowContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        CodeField(
            value = draft.path,
            label = correctedString(R.string.developer_console_path),
            singleLine = true,
            keyboardType = KeyboardType.Uri,
            onChange = { path -> onEdit { it.copy(path = path) } },
        )
        CodeField(
            value = draft.headers,
            label = correctedString(R.string.developer_console_headers),
            onChange = { headers -> onEdit { it.copy(headers = headers) } },
        )
        if (draft.method.carriesBody) {
            CodeField(
                value = draft.body,
                label = correctedString(R.string.developer_console_body),
                onChange = { body -> onEdit { it.copy(body = body) } },
            )
        }
    }
}

@Composable
private fun CodeField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Ascii,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        label = { Text(text = label, style = LocalTextStyle.current.emphasised(focused)) },
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
        ),
        interactionSource = interactionSource,
    )
}

/** The answer, or why there is none. Selectable, and copyable whole. */
@Composable
private fun ConsoleResult(outcome: ConsoleOutcome, onCopy: (String) -> Unit) {
    when (outcome) {
        is ConsoleOutcome.Refused -> GroupItem(
            title = refusalText(outcome.reason),
            subtitle = outcome.detail,
            tone = errorTone(),
        )
        is ConsoleOutcome.Failed -> GroupItem(
            title = correctedLine(R.string.developer_console_failed, outcome.description),
            subtitle = correctedString(R.string.developer_took_ms, "—", outcome.tookMillis),
            tone = errorTone(),
        )
        is ConsoleOutcome.Answered -> AnswerRows(outcome.answer, onCopy)
    }
}

@Composable
private fun AnswerRows(answer: ConsoleAnswer, onCopy: (String) -> Unit) {
    GroupItem(
        title = correctedLine(
            R.string.developer_took_ms,
            "HTTP ${answer.status} ${answer.message}".trim(),
            answer.tookMillis,
        ),
        subtitle = answer.url,
        tone = if (answer.status >= FirstErrorStatus) errorTone() else accentTone(1),
    )
    CodeBlock(answer.headers.joinToString("\n") { (name, value) -> "$name: $value" })
    if (answer.body.isNotEmpty()) {
        CodeBlock(answer.body)
        if (answer.truncated) {
            GroupItem(title = correctedString(R.string.developer_console_truncated), tone = neutralTone())
        }
        GroupActionItem(
            label = correctedString(R.string.developer_console_copy),
            icon = Icons.Rounded.ContentCopy,
            onClick = { onCopy(answer.body) },
        )
    }
}

/** Long lines scroll sideways rather than wrap: a JSON answer reads by its indentation. */
@Composable
private fun CodeBlock(text: String) {
    if (text.isEmpty()) return
    SelectionContainer(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.rowContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
        )
    }
}

@Composable
private fun refusalText(reason: ConsoleRefusal): String = correctedString(
    when (reason) {
        ConsoleRefusal.NO_SERVER -> R.string.developer_console_refused_no_server
        ConsoleRefusal.SERVER_NEEDS_HTTPS -> R.string.developer_console_refused_https
        ConsoleRefusal.NO_ORIGIN -> R.string.developer_console_refused_no_origin
        ConsoleRefusal.ORIGIN_NOT_ALLOWED -> R.string.developer_console_refused_origin
        ConsoleRefusal.BAD_PATH -> R.string.developer_console_refused_path
        ConsoleRefusal.LEAVES_ORIGIN -> R.string.developer_console_refused_leaves
        ConsoleRefusal.BAD_HEADER -> R.string.developer_console_refused_header
        ConsoleRefusal.FORBIDDEN_HEADER -> R.string.developer_console_refused_forbidden
        ConsoleRefusal.AUTH_TWICE -> R.string.developer_console_refused_auth_twice
        ConsoleRefusal.AUTH_OFF_SERVER -> R.string.developer_console_refused_auth_off_server
        ConsoleRefusal.NO_TOKEN -> R.string.developer_console_refused_no_token
        ConsoleRefusal.BODY_NOT_ALLOWED -> R.string.developer_console_refused_body
    },
)

/** A «Выбрать» at the end of a row that opens a menu of [items] in place. */
@Composable
private fun <T> Picker(items: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = items.isNotEmpty()) {
            Text(text = correctedString(R.string.developer_console_pick))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(text = label(item), fontFamily = FontFamily.Monospace) },
                    onClick = {
                        open = false
                        onPick(item)
                    },
                )
            }
        }
    }
}

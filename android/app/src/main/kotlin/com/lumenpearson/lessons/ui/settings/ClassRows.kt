package com.lumenpearson.lessons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.ui.common.ClassCodeLengths
import com.lumenpearson.lessons.ui.join.JoinViewModel
import com.lumenpearson.lessons.ui.join.rememberJoinSubmit
import com.lumenpearson.lessons.ui.join.asText

/**
 * The class group: every class this phone has joined, and the ways in and out.
 *
 * One row per membership rather than one row for "the class", because the app
 * holds several and shows one — see [com.lumenpearson.lessons.core.data.repository.SessionRepository].
 * Tapping a row switches to that class; the tick says which one is on screen
 * now, and it is a tick rather than a highlighted row because the rows are
 * already coloured by position and a second colour would say nothing.
 *
 * Leaving is split in two once there is more than one class, and deliberately
 * not before: with a single class "выйти" has always meant "leave it", and
 * having that one row quietly change meaning the day somebody adds a second
 * class is how a person ends up deleting both.
 */
internal fun LazyListScope.classRows(
    state: SettingsUiState,
    onSelectClass: (Long) -> Unit,
    onAddClass: () -> Unit,
    onLeaveClass: (Session) -> Unit,
    onSignOut: () -> Unit,
) = item(key = "class") {
    val classes = state.sessions
    val activeId = state.session?.classId
    SettingsGroup(
        title = correctedString(
            if (classes.size > 1) R.string.settings_classes_group else R.string.settings_class_group,
        ),
    ) {
        if (classes.isEmpty()) {
            // Only reachable for the instant between the last class being left
            // and the shell noticing; the page is still composed while that
            // happens, and an empty group reads as a broken screen.
            GroupItem(
                title = correctedString(R.string.settings_class_unknown),
                subtitle = correctedString(R.string.settings_class_no_school),
                icon = Icons.Rounded.School,
                tone = accentTone(1),
            )
        }
        classes.forEachIndexed { index, session ->
            val active = session.classId == activeId
            GroupItem(
                title = session.className,
                subtitle = session.school ?: correctedString(R.string.settings_class_no_school),
                icon = Icons.Rounded.School,
                tone = accentTone(index + 1),
                // The row that is already showing is not a button. Leaving it
                // clickable would make the tick look like a toggle that does
                // nothing, and the repository would drop the call anyway.
                onClick = if (active) null else ({ onSelectClass(session.classId) }),
                trailing = if (active) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = correctedString(R.string.settings_class_active),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
            )
        }
        GroupItem(
            title = correctedString(R.string.settings_class_add),
            icon = Icons.Rounded.Add,
            tone = accentTone(0),
            onClick = onAddClass,
        )
        if (classes.size > 1 && state.session != null) {
            GroupItem(
                title = correctedString(R.string.settings_class_leave, state.session.className),
                icon = Icons.AutoMirrored.Rounded.Logout,
                tone = errorTone(),
                onClick = { onLeaveClass(state.session) },
            )
        }
        GroupItem(
            title = correctedString(
                if (classes.size > 1) R.string.settings_sign_out_all else R.string.settings_sign_out,
            ),
            icon = Icons.AutoMirrored.Rounded.Logout,
            tone = errorTone(),
            onClick = onSignOut,
        )
    }
}

/**
 * Joining a second class without leaving the first.
 *
 * It drives [JoinViewModel], the same one the join screen uses, so a code typed
 * here is normalized, validated and reported on exactly as it is on the way in
 * — including the first sync that follows a success, which is what stops the
 * class that was just added from being empty for the next hour.
 *
 * Dismissal watches the view model's own one-shot rather than the class on
 * screen. The class id looked like the success signal and is not one: entering
 * the code of the class already showing is a real case — it is how somebody
 * whose device was revoked gets back in — and it succeeds without changing
 * which class is active, so a sheet keyed on that sat there after a successful
 * join with the code still in the field and nothing to say it had worked.
 *
 * @param message the sentence under the title. A second class by default; the
 *   diary home, where the phone is in no class, passes one about a first.
 */
@Composable
internal fun AddClassSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes message: Int = R.string.settings_class_add_message,
    viewModel: JoinViewModel = viewModel(factory = JoinViewModel.Factory),
) {
    val join by viewModel.uiState.collectAsStateWithLifecycle()

    // Closing empties the field, because the view model outlives the sheet: it
    // is scoped to the screen, so without this, opening «Добавить класс» again
    // — to add a third class, or after getting a code wrong — starts with the
    // previous attempt still typed in. Cleared on the way out rather than on
    // the way in, so a rotation, which recomposes the sheet without closing it,
    // does not throw away what the user is halfway through typing.
    val close = {
        viewModel.onCodeChange("")
        onDismiss()
    }

    // Dismissed from an effect rather than in the body: dismissing is a state
    // change, and composition is not allowed to make one. Done inline it
    // dismissed on the same frame it was deciding what to draw, which Compose
    // is entitled to treat as an infinite recomposition. Only on this sheet's
    // own join: another caller's, landing late, closed it before a code could
    // be typed.
    val submit = rememberJoinSubmit(viewModel, join.joined) { close() }

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val errorText = join.error.asText()

    LessonsBottomSheet(
        onDismissRequest = close,
        modifier = modifier,
        title = correctedString(R.string.settings_class_add),
    ) {
        Text(
            text = correctedString(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        OutlinedTextField(
            value = join.code,
            onValueChange = viewModel::onCodeChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding),
            singleLine = true,
            isError = errorText != null,
            interactionSource = interactionSource,
            label = {
                Text(
                    text = correctedString(R.string.join_code_label),
                    style = LocalTextStyle.current.emphasised(focused),
                )
            },
            supportingText = {
                Text(
                    text = errorText ?: correctedString(
                        R.string.join_code_hint,
                        ClassCodeLengths.first,
                        ClassCodeLengths.last,
                    ),
                )
            },
            keyboardOptions = KeyboardOptions(
                // The view model upper-cases anyway; asking the keyboard for
                // capitals as well means the letters look right while they are
                // being typed rather than jumping afterwards.
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = close) {
                Text(text = correctedString(R.string.action_cancel))
            }
            Button(onClick = submit, enabled = join.canSubmit) {
                Text(text = correctedString(R.string.join_action))
            }
        }
    }
}

/** Confirms leaving one class while the others stay. @see classRows */
@Composable
internal fun LeaveClassSheet(
    className: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = correctedString(R.string.settings_class_leave_title),
    ) {
        Text(
            text = correctedString(R.string.settings_class_leave_message, className),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = correctedString(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                // Not `settings_sign_out`: this sheet drops one class and the
                // others stay, and in English that string reads "Sign out"
                // under a title that says "Leave this class?".
                Text(text = correctedString(R.string.settings_class_leave_action))
            }
        }
    }
}

package com.lumenpearson.lessons.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.emphasised

/**
 * Editor for `AppSettings.baseUrl`.
 *
 * Shared by the join screen and settings, because a wrong server address is the
 * one thing that makes joining impossible and the user has to be able to fix it
 * *before* they have an account as well as after.
 *
 * A bottom sheet rather than a dialog: this is the app's one text-entry
 * interruption, the keyboard covers half the screen while it is open, and a
 * sheet is anchored to the same edge the keyboard comes from. Essentials puts
 * every input of this kind in a sheet for the same reason.
 *
 * @param initialUrl current address; the field starts from it rather than empty
 *   so a small typo is a small edit.
 * @param onConfirm called with the trimmed value; the caller decides whether an
 *   empty string means "use the default".
 */
@Composable
fun ServerUrlSheet(
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    val trimmed = remember(url) { url.trim() }
    // Held only to know whether the field has focus: the label is bold while
    // it does, which is the one accent a text field gets.
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.server_dialog_title),
    ) {
        Text(
            text = stringResource(R.string.server_dialog_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding),
            singleLine = true,
            interactionSource = interactionSource,
            label = {
                Text(
                    text = stringResource(R.string.server_dialog_label),
                    style = LocalTextStyle.current.emphasised(focused),
                )
            },
            placeholder = { Text(text = stringResource(R.string.server_dialog_placeholder)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
            // Enabled on an empty field too: the callers treat "" as "use the
            // default", and with the button disabled there was no way to undo a
            // mistyped address short of reinstalling.
            Button(onClick = { onConfirm(trimmed) }) {
                Text(text = stringResource(R.string.action_save))
            }
        }
    }
}

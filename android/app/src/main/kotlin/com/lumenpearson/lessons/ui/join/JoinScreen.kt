package com.lumenpearson.lessons.ui.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.ui.common.ClassCodeLength
import com.lumenpearson.lessons.ui.common.ServerUrlSheet

/**
 * First run: turn a six-character code from the classroom whiteboard into a
 * session.
 *
 * There is no navigation callback — a successful join writes a session and the
 * app shell reacts to it. The screen's only jobs are validation feedback and
 * giving a pupil whose school runs its own server a way to point the app at it.
 */
@Composable
fun JoinScreen(
    modifier: Modifier = Modifier,
    viewModel: JoinViewModel = viewModel(factory = JoinViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showServerDialog by rememberSaveable { mutableStateOf(false) }

    if (showServerDialog) {
        ServerUrlSheet(
            initialUrl = state.baseUrl,
            onDismiss = { showServerDialog = false },
            onConfirm = { url ->
                viewModel.onServerUrlChange(url)
                showServerDialog = false
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.join_title),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.join_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))
            ClassCodeField(
                code = state.code,
                error = state.error,
                enabled = !state.isSubmitting,
                onCodeChange = viewModel::onCodeChange,
                onSubmit = viewModel::submit,
            )

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                }
                Text(text = stringResource(R.string.join_action))
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { showServerDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = state.baseUrl.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.join_server_current, it) }
                        ?: stringResource(R.string.join_server_change),
                )
            }
        }
    }
}

/**
 * The code input.
 *
 * Oversized, centred and letter-spaced on purpose: it is the only control on the
 * screen, it is copied character by character off a board, and a big field makes
 * a mistyped character obvious before the request is sent.
 */
@Composable
private fun ClassCodeField(
    code: String,
    error: JoinError?,
    enabled: Boolean,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val errorText = error.asText()

    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        textStyle = MaterialTheme.typography.displaySmall.copy(
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 12.sp,
        ),
        label = { Text(text = stringResource(R.string.join_code_label)) },
        supportingText = {
            Text(
                text = errorText ?: stringResource(R.string.join_code_hint, ClassCodeLength),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            // fallback: on foundation < 1.7 this parameter is named `autoCorrect`.
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboard?.hide()
                onSubmit()
            },
        ),
    )
}

/** Localizes the inline error, or `null` when the field is fine. */
@Composable
private fun JoinError?.asText(): String? = when (this) {
    null -> null
    JoinError.InvalidCode -> stringResource(R.string.join_error_invalid_code, ClassCodeLength)
    is JoinError.Rejected ->
        detail?.let { stringResource(R.string.join_error_rejected, it) }
            ?: stringResource(R.string.join_error_generic)
}

package com.lumenpearson.lessons.ui.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.GoogleSansFlexRounded
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.common.ClassCodeLength
import com.lumenpearson.lessons.ui.common.ServerUrlSheet

/** Height of the primary action, straight from the Essentials onboarding. */
private val ActionHeight = 56.dp

/**
 * First run: turn a six-character code from the classroom whiteboard into a
 * session.
 *
 * Shaped like the first-run flow in
 * [Essentials](https://github.com/sameerasw/essentials): a centred mark, a
 * rounded headline, the choices as grouped rows, and one full-width action
 * pinned to the bottom edge carrying its label on the left and an arrow on the
 * right. The content above it scrolls; the action does not move.
 *
 * There is no navigation callback — a successful join writes a session and the
 * app shell reacts to it. The screen's own jobs are to say what the app will do
 * once it has a class, to take the code, and to give a pupil whose school runs
 * its own server a way to point the app at it.
 */
@Composable
fun JoinScreen(
    modifier: Modifier = Modifier,
    viewModel: JoinViewModel = viewModel(factory = JoinViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showServerSheet by rememberSaveable { mutableStateOf(false) }

    if (showServerSheet) {
        ServerUrlSheet(
            initialUrl = state.baseUrl,
            onDismiss = { showServerSheet = false },
            onConfirm = { url ->
                viewModel.onServerUrlChange(url)
                showServerSheet = false
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
                // On the outer column, so the keyboard shrinks the scrolling
                // area and lifts the action, instead of growing the content
                // inside a viewport that never moved.
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                Icon(
                    imageVector = Icons.Rounded.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )

                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.join_title),
                    // The rounded axis of the app's own face, which is what
                    // Essentials reaches for on the one screen that is mostly
                    // one sentence.
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = GoogleSansFlexRounded,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.join_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(28.dp))
                ClassCodeField(
                    code = state.code,
                    error = state.error,
                    enabled = !state.isSubmitting,
                    onCodeChange = viewModel::onCodeChange,
                    onSubmit = viewModel::submit,
                )

                Spacer(Modifier.height(GroupSpacing))
                WhatYouGet()

                Spacer(Modifier.height(GroupSpacing))
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = stringResource(R.string.join_server_section))
                    RoundedCardContainer {
                        GroupLinkItem(
                            title = stringResource(R.string.settings_server_url),
                            subtitle = state.baseUrl.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.join_server_unset),
                            icon = Icons.Rounded.Dns,
                            tone = accentTone(0),
                            onClick = { showServerSheet = true },
                        )
                    }
                }
                Spacer(Modifier.height(GroupSpacing))
            }

            JoinAction(
                enabled = state.canSubmit,
                submitting = state.isSubmitting,
                onClick = viewModel::submit,
            )
        }
    }
}

/**
 * The three rows that say what a class code actually buys.
 *
 * A first-run screen that is one field and one button tells a pupil nothing
 * about what they are joining. Essentials spends a whole onboarding step on
 * this; one group of rows is this app's share of it.
 */
@Composable
private fun WhatYouGet(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.join_what_you_get))
        RoundedCardContainer {
            GroupItem(
                title = stringResource(R.string.join_feature_timetable),
                subtitle = stringResource(R.string.join_feature_timetable_description),
                icon = Icons.Rounded.CalendarMonth,
                tone = accentTone(1),
            )
            GroupItem(
                title = stringResource(R.string.join_feature_homework),
                subtitle = stringResource(R.string.join_feature_homework_description),
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                tone = accentTone(3),
            )
            GroupItem(
                title = stringResource(R.string.join_feature_widget),
                subtitle = stringResource(R.string.join_feature_widget_description),
                icon = Icons.Rounded.Widgets,
                tone = accentTone(5),
            )
        }
    }
}

/**
 * The one action, pinned to the bottom edge.
 *
 * The shape is Essentials' own: full width, 56 dp tall, the label on the left in
 * bold `titleMedium`, and the arrow pushed to the right by a weighted spacer, so
 * the two ends of the button read as "what this does" and "where it goes".
 * While the request is in flight the arrow is replaced by a spinner rather than
 * the whole button by a different one, which would move the label.
 */
@Composable
private fun JoinAction(
    enabled: Boolean,
    submitting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(ScreenPadding)
            .height(ActionHeight),
    ) {
        Text(
            text = stringResource(R.string.join_action),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.weight(1f))

        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The code input.
 *
 * Oversized, centred and letter-spaced on purpose: it is copied character by
 * character off a board, and a big field makes a mistyped character obvious
 * before the request is sent.
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
        shape = MaterialTheme.shapes.large,
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

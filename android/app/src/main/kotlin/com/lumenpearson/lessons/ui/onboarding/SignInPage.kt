package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsDialog
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.navigation.isInsecure
import com.lumenpearson.lessons.ui.common.ServerUrlSheet
import com.lumenpearson.lessons.ui.common.openInBrowser
import com.lumenpearson.lessons.ui.diary.DiaryCredentialFields
import com.lumenpearson.lessons.ui.diary.asText
import com.lumenpearson.lessons.ui.diary.regionName
import com.lumenpearson.lessons.ui.diary.systemName

/**
 * The family's own sign-in: the app's clearly labelled form, and a card above
 * it saying where the password goes — the diary's host, from the same
 * allow-list the sign-in is held to — before a single character is typed.
 *
 * The password goes from [DiaryCredentialFields] straight to
 * [OnboardingViewModel.submitSignIn] and on to the diary; our server is handed
 * the session the diary issues, never the password. A failure is a pop-up in
 * `DiaryProblemText`'s words (the keyboard hides a line under the button), and
 * what can be done about it stays on the page after the pop-up is gone:
 * re-sending the held session without the password, setting the server, the
 * class code, or the Госуслуги page in the browser.
 */
@Composable
internal fun SignInPage(
    viewModel: OnboardingViewModel,
    onBack: (() -> Unit)?,
) {
    val header by viewModel.signInHeader.collectAsStateWithLifecycle()
    val ui by viewModel.signIn.state.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingServer by rememberSaveable { mutableStateOf(false) }
    val current = header
    val problem = ui.problem

    if (editingServer) {
        ServerUrlSheet(
            initialUrl = baseUrl,
            description = correctedString(R.string.onboarding_sign_in_needs_server),
            onDismiss = { editingServer = false },
            onConfirm = { url ->
                viewModel.setServer(url)
                editingServer = false
            },
        )
    }
    if (ui.dialog && problem != null) {
        LessonsDialog(
            title = correctedString(R.string.diary_sign_in_failed_title),
            message = problem.asText(),
            confirmLabel = correctedString(R.string.diary_dialog_dismiss),
            onDismiss = viewModel.signIn::dismissDialog,
        )
    }

    StepScaffold(
        actions = {
            when {
                current?.classBound == true -> OnboardingActions(
                    label = correctedString(R.string.onboarding_sign_in_skip),
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onBack = onBack,
                    enabled = !ui.busy,
                    onClick = viewModel::skipSignIn,
                )
                onBack != null -> OnboardingBackRow(onBack = onBack)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
        ) {
            Spacer(Modifier.height(8.dp))
            if (current == null) {
                NoteCard(correctedString(R.string.onboarding_sign_in_no_target))
                return@Column
            }
            val system = current.place.systemName().orEmpty()
            OnboardingTitle(
                title = if (current.classBound) {
                    correctedString(R.string.onboarding_sign_in_class_title)
                } else {
                    correctedString(R.string.onboarding_sign_in_title, system)
                },
                subtitle = when {
                    current.classBound -> correctedString(
                        R.string.onboarding_sign_in_class_subtitle,
                        current.className.orEmpty(),
                        system,
                    )
                    current.provider == DiaryProviderKey.PETERSBURG ->
                        correctedString(R.string.onboarding_sign_in_account)
                    else -> correctedString(
                        R.string.onboarding_sign_in_where,
                        current.place.regionName().orEmpty(),
                        current.schoolName.orEmpty(),
                    )
                },
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = correctedString(R.string.onboarding_sign_in_privacy_title))
                NoteCard(
                    listOfNotNull(
                        current.place.host?.let { correctedString(R.string.diary_password_destination, it) },
                        correctedString(R.string.diary_password_notice),
                    ).joinToString("\n\n"),
                )
            }

            if (isInsecure(baseUrl)) NoteCard(correctedString(R.string.diary_insecure_server))

            val petersburg = current.provider == DiaryProviderKey.PETERSBURG
            DiaryCredentialFields(
                login = ui.login,
                onLoginChange = viewModel.signIn::setLogin,
                onSubmit = { _, password -> viewModel.submitSignIn(password) },
                busy = ui.busy,
                failed = problem != null,
                onEdited = viewModel.signIn::edited,
                loginLabel = correctedString(
                    if (petersburg) R.string.onboarding_sign_in_email else R.string.diary_login_label,
                ),
                loginKeyboard = if (petersburg) KeyboardType.Email else KeyboardType.Text,
            )

            ui.stage?.let { stage ->
                Text(
                    text = when (stage) {
                        SignInStage.CHECKING -> correctedString(R.string.onboarding_sign_in_stage_checking)
                        SignInStage.UPSTREAM -> correctedString(R.string.onboarding_sign_in_stage_upstream, system)
                        SignInStage.REGISTER -> correctedString(R.string.onboarding_sign_in_stage_register)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (problem != null && !ui.busy) {
                ProblemActions(
                    problem = problem,
                    sessionHeld = ui.sessionHeld,
                    classBound = current.classBound,
                    onRetry = viewModel::retrySignIn,
                    onServer = { editingServer = true },
                    onClassCode = viewModel::toClassCode,
                    onOpen = { url -> openInBrowser(context, url) },
                )
            }

            current.forgotUrl?.let { url ->
                TextButton(onClick = { openInBrowser(context, url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(correctedString(R.string.onboarding_sign_in_forgot))
                }
            }

            // The server that will hold the session, named where the session is
            // about to be sent to it.
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = correctedString(R.string.join_server_section))
                RoundedCardContainer {
                    GroupLinkItem(
                        title = correctedString(R.string.settings_server_url),
                        subtitle = baseUrl.takeIf { it.isNotBlank() }
                            ?: correctedString(R.string.onboarding_way_server_unset),
                        icon = Icons.Rounded.Dns,
                        tone = accentTone(0),
                        onClick = { editingServer = true },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** What can be done about [problem], as buttons that outlive its pop-up. */
@Composable
private fun ProblemActions(
    problem: DiarySignInProblem,
    sessionHeld: Boolean,
    classBound: Boolean,
    onRetry: () -> Unit,
    onServer: () -> Unit,
    onClassCode: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val handoff = (problem as? DiarySignInProblem.GosuslugiOnly)?.handoffUrl?.takeIf { it.startsWith("https://") }
    val retry = sessionHeld && problem.retryKeepsSession
    val server = problem.action == DiarySignInProblem.Action.SET_SERVER
    // A class code is the way round a diary that refuses our server — unless a
    // class is what brought the family here.
    val code = problem is DiarySignInProblem.ServerRefusedSession && !classBound
    if (!retry && !server && !code && handoff == null) return
    RoundedCardContainer {
        if (retry) {
            GroupActionItem(
                label = correctedString(R.string.onboarding_sign_in_retry),
                icon = Icons.Rounded.Refresh,
                onClick = onRetry,
            )
        }
        if (server) {
            GroupActionItem(
                label = correctedString(R.string.onboarding_sign_in_set_server),
                icon = Icons.Rounded.Dns,
                onClick = onServer,
            )
        }
        if (handoff != null) {
            GroupActionItem(
                label = correctedString(R.string.onboarding_handoff_open),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                onClick = { onOpen(handoff) },
            )
        }
        if (code) {
            GroupActionItem(
                label = correctedString(R.string.onboarding_provider_code_title),
                icon = Icons.Rounded.Key,
                onClick = onClassCode,
            )
        }
    }
}

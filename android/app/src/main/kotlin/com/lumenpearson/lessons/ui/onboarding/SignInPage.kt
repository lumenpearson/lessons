package com.lumenpearson.lessons.ui.onboarding

import android.content.Context
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 *
 * The way out is [signInExitOf]'s, and it is held while a sign-in is under
 * way (see [OnboardingViewModel.back]).
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
    val links = remember(context) { BrowserLinks(context) }
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
            // The first run's wording: no settings exist yet, and a family that
            // came through its class is not advised to join one.
            message = problem.asText(firstRun = true, inClass = current?.classBound == true),
            confirmLabel = correctedString(R.string.diary_dialog_dismiss),
            onDismiss = viewModel.signIn::dismissDialog,
        )
    }

    val exit = signInExitOf(classBound = current?.classBound == true, canGoBack = onBack != null)
    StepScaffold(
        actions = {
            when (exit) {
                SignInExit.SKIP -> OnboardingActions(
                    label = correctedString(R.string.onboarding_sign_in_skip),
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onBack = onBack,
                    enabled = !ui.busy,
                    onClick = viewModel::skipSignIn,
                )
                SignInExit.BACK -> onBack?.let { OnboardingBackRow(onBack = it, enabled = !ui.busy) }
                // In the page, under the form, as the import page has it.
                SignInExit.START_OVER -> Unit
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
                if (exit == SignInExit.START_OVER) StartOverButton(enabled = !ui.busy, onClick = viewModel::startOver)
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
                    onOpen = links::open,
                )
            }

            current.forgotUrl?.let { url ->
                TextButton(onClick = { links.open(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(correctedString(R.string.onboarding_sign_in_forgot))
                }
            }
            // The handoff sheet's own sentence: a tap that opened nothing
            // must not read as a button that does nothing.
            if (links.failed) {
                Text(
                    text = correctedString(R.string.onboarding_handoff_no_browser),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (exit == SignInExit.START_OVER) StartOverButton(enabled = !ui.busy, onClick = viewModel::startOver)

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

/** «Выйти из дневника и начать заново», drawn as the import page draws it. */
@Composable
private fun StartOverButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(correctedString(R.string.onboarding_import_restart))
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
    // No answer from our server is as often a mistyped address as no network,
    // and the address is on this page.
    val server = problem.action == DiarySignInProblem.Action.SET_SERVER ||
        problem == DiarySignInProblem.Offline
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

/** What leaves the sign-in, other than signing in. */
internal enum class SignInExit {
    /** «Не сейчас»: a class's diary, whose class is joined whatever happens here. */
    SKIP,

    /** The back square, to the step the form was reached from. */
    BACK,

    /**
     * «Выйти из дневника и начать заново», the import page's own way out, for
     * a sign-in with nothing behind it — one reopened after an import the
     * diary ended sits on the floor. Without it a sign-in that keeps failing
     * there has no exit short of killing the app.
     */
    START_OVER,
}

internal fun signInExitOf(classBound: Boolean, canGoBack: Boolean): SignInExit = when {
    classBound -> SignInExit.SKIP
    canGoBack -> SignInExit.BACK
    else -> SignInExit.START_OVER
}

/**
 * Opens the diary's pages from the sign-in — «Забыли пароль?» and the
 * Госуслуги handoff — and remembers that nothing on the phone could, so the
 * page can say so as the handoff sheet does. [openInBrowser] answers `false`
 * on a phone with no browser, and a dropped answer is a tap that does nothing.
 */
@Stable
internal class BrowserLinks(private val context: Context) {
    var failed by mutableStateOf(false)
        private set

    fun open(url: String) {
        failed = !openInBrowser(context, url)
    }
}

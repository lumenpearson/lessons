package com.lumenpearson.lessons.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.BuildConfig
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.github.BugReportComposer
import java.net.URLEncoder

/**
 * Which of the settings sheets is up.
 *
 * Four flags in one saveable holder rather than four `rememberSaveable`s in the
 * screen, so the screen's body stays a list of rows and the sheets stay a
 * list of sheets.
 */
@Stable
class SupportSheetState internal constructor(
    prerelease: Boolean,
    licenses: Boolean,
    bugReport: Boolean,
    signIn: Boolean,
) {
    var prerelease: Boolean by mutableStateOf(prerelease)
    var licenses: Boolean by mutableStateOf(licenses)
    var bugReport: Boolean by mutableStateOf(bugReport)
    var signIn: Boolean by mutableStateOf(signIn)

    companion object {
        internal val Saver = listSaver<SupportSheetState, Boolean>(
            save = { listOf(it.prerelease, it.licenses, it.bugReport, it.signIn) },
            restore = { SupportSheetState(it[0], it[1], it[2], it[3]) },
        )
    }
}

@Composable
internal fun rememberSupportSheets(): SupportSheetState =
    rememberSaveable(saver = SupportSheetState.Saver) {
        SupportSheetState(prerelease = false, licenses = false, bugReport = false, signIn = false)
    }

/**
 * The sheets the settings pages can raise, all hosted in one place.
 *
 * The release sheet is deliberately not here: it is also raised by the shell
 * on launch, so the shell hosts it (see `UpdateHost`).
 */
@Composable
internal fun SupportSheets(
    sheets: SupportSheetState,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    if (sheets.prerelease) {
        PrereleaseSheet(
            onDismiss = { sheets.prerelease = false },
            onConfirm = {
                sheets.prerelease = false
                viewModel.enablePrereleases()
            },
        )
    }

    if (sheets.licenses) {
        LicensesSheet(onDismiss = { sheets.licenses = false })
    }

    if (sheets.signIn) {
        GithubSignInSheet(
            flow = state.signIn,
            onDismiss = {
                sheets.signIn = false
                // Closing the sheet with a code still on it means "never
                // mind": the polling that would otherwise keep going for
                // fifteen minutes is for a person who is no longer waiting.
                viewModel.cancelGithubSignIn()
            },
            onOpenLogin = uriHandler::openUri,
            onRetry = viewModel::signInWithGithub,
        )
    }

    if (sheets.bugReport) {
        val version = viewModel.installedVersion
        val subject = stringResource(R.string.bug_report_email_subject, version)
        BugReportSheet(
            deviceInfo = BugReportComposer.deviceInfoLines(version),
            isSignedIn = state.github != null,
            isSending = state.isFilingIssue,
            canEmail = BuildConfig.CONTACT_EMAIL.isNotBlank(),
            onDismiss = { sheets.bugReport = false },
            onSend = { description, email ->
                val draft = BugReportComposer.compose(description, email, version)
                viewModel.fileIssue(draft) { url ->
                    sheets.bugReport = false
                    uriHandler.openUri(url)
                }
            },
            onOpenIssuePage = { description ->
                val draft = BugReportComposer.compose(description, null, version)
                uriHandler.openUri(newIssueUrl(draft.title, draft.body))
            },
            onEmail = { description, deviceBlock ->
                val send = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.CONTACT_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, description + "\n\n" + deviceBlock)
                }
                // No chooser: SENDTO with a mailto: URI already goes only to
                // mail apps, and a phone without one gets nothing rather than
                // a crash.
                runCatching { context.startActivity(send) }
            },
        )
    }
}

/**
 * GitHub's "new issue" page with the title and body filled in.
 *
 * The way to file without signing in: the browser has the user's session, so
 * the app never needs one. The body is the same block the signed-in path sends.
 */
private fun newIssueUrl(title: String, body: String): String {
    fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    return "https://github.com/lumenpearson/lessons/issues/new?title=${enc(title)}&body=${enc(body)}"
}

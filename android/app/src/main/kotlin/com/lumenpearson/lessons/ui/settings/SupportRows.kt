package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Login
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/**
 * The rows under "О приложении" that talk to GitHub.
 *
 * A bug report, and the sign-in that lets it be filed from here rather than
 * from a browser. The sign-in row exists only in a build that has a client id
 * to sign in with: an OAuth App is registered by whoever builds the app, and a
 * row that opened a sheet to say "not configured" would be a row about the
 * build, not about the user.
 */
internal fun LazyListScope.supportRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onReportBug: () -> Unit,
    onSignIn: () -> Unit,
    onShowLicenses: () -> Unit,
) {
    item(key = "support") {
        SettingsGroup(title = stringResource(R.string.settings_github_group)) {
            val account = state.github
            when {
                account != null -> GroupItem(
                    title = stringResource(R.string.settings_github_signed_in_as, account.login),
                    subtitle = stringResource(R.string.settings_github_sign_in_description),
                    icon = Icons.Rounded.Code,
                    tone = accentTone(3),
                )
                state.githubConfigured -> GroupLinkItem(
                    title = stringResource(R.string.settings_github_sign_in),
                    subtitle = stringResource(R.string.settings_github_sign_in_description),
                    icon = Icons.Rounded.Login,
                    tone = accentTone(3),
                    onClick = onSignIn,
                )
            }
            GroupLinkItem(
                title = stringResource(R.string.settings_bug_report),
                subtitle = stringResource(R.string.settings_bug_report_description),
                icon = Icons.Rounded.BugReport,
                tone = accentTone(1),
                onClick = onReportBug,
            )
            GroupLinkItem(
                title = stringResource(R.string.settings_licenses),
                subtitle = stringResource(R.string.settings_licenses_description),
                icon = Icons.Rounded.Gavel,
                tone = accentTone(0),
                onClick = onShowLicenses,
            )
            if (account != null) {
                GroupItem(
                    title = stringResource(R.string.settings_github_sign_out),
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    tone = errorTone(),
                    onClick = viewModel::signOutOfGithub,
                )
            }
        }
    }
}

package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Gavel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/**
 * The rows under "О приложении" that talk to GitHub.
 *
 * A bug report, the licences, and the way out of the account the report is
 * filed with. Signing *in* is offered in the translation group instead, which
 * is where an account is first needed and where Essentials puts it; two rows on
 * one page showing the same account state is one row too many. Signing out
 * stays here, next to the thing the account is otherwise for.
 */
internal fun LazyListScope.supportRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onReportBug: () -> Unit,
    onShowLicenses: () -> Unit,
) {
    item(key = "support") {
        SettingsGroup(title = correctedString(R.string.settings_github_group)) {
            val account = state.github
            GroupLinkItem(
                title = correctedString(R.string.settings_bug_report),
                subtitle = correctedString(R.string.settings_bug_report_description),
                icon = Icons.Rounded.BugReport,
                tone = accentTone(1),
                onClick = onReportBug,
            )
            GroupLinkItem(
                title = correctedString(R.string.settings_licenses),
                subtitle = correctedString(R.string.settings_licenses_description),
                icon = Icons.Rounded.Gavel,
                tone = accentTone(0),
                onClick = onShowLicenses,
            )
            if (account != null) {
                GroupItem(
                    title = correctedString(R.string.settings_github_sign_out),
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    tone = errorTone(),
                    onClick = viewModel::signOutOfGithub,
                )
            }
        }
    }
}

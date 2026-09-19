package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Verified
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.UpdateCheck
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/**
 * The "Обновления" page.
 *
 * Modelled on the Updates block of Essentials' settings: a row that says what
 * is installed and what the last check found, a button to check now, and three
 * switches for how the automatic check behaves. The release sheet itself is
 * raised by the caller through [onShowRelease], because the same sheet is also
 * raised by the app shell when the automatic check finds something, and one
 * host per sheet is enough.
 *
 * @param onShowRelease open the release sheet for whatever the last check found.
 * @param onAskPrerelease the pre-release switch was turned *on*; the caller
 *   confirms first, because a tester who did not mean to opt in should not be
 *   offered an unstable build by accident.
 */
internal fun LazyListScope.updateRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onShowRelease: () -> Unit,
    onAskPrerelease: () -> Unit,
) {
    item(key = "version") {
        SettingsGroup(title = correctedString(R.string.settings_updates_group)) {
            val check = state.update
            val installed = correctedString(R.string.settings_updates_installed, viewModel.installedVersion)
            // The row's title is the verdict and its subtitle the installed
            // version, so a glance at the page answers "am I current" before
            // anything is tapped. Checking and Failed are verdicts too.
            val (verdict, icon, tone) = when (check) {
                UpdateCheck.Idle -> Triple(
                    correctedString(R.string.settings_updates_never),
                    Icons.Rounded.Update,
                    accentTone(0),
                )
                UpdateCheck.Checking -> Triple(
                    correctedString(R.string.settings_updates_checking),
                    Icons.Rounded.Update,
                    accentTone(0),
                )
                is UpdateCheck.UpToDate -> Triple(
                    correctedString(R.string.settings_updates_up_to_date),
                    Icons.Rounded.Verified,
                    accentTone(3),
                )
                is UpdateCheck.Available -> Triple(
                    correctedString(R.string.settings_updates_available, check.release.tag),
                    Icons.Rounded.NewReleases,
                    accentTone(1),
                )
                is UpdateCheck.Failed -> Triple(
                    correctedString(R.string.settings_updates_failed),
                    Icons.Rounded.Update,
                    errorTone(),
                )
            }
            GroupLinkItem(
                title = verdict,
                subtitle = installed,
                icon = icon,
                tone = tone,
                onClick = onShowRelease,
            )
            GroupItem(
                title = correctedString(R.string.settings_updates_check),
                icon = Icons.Rounded.SystemUpdate,
                tone = accentTone(4),
                enabled = check != UpdateCheck.Checking,
                onClick = viewModel::checkForUpdates,
            )
        }
    }

    item(key = "update_behaviour") {
        SettingsGroup(title = correctedString(R.string.settings_updates_behaviour_group)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_updates_auto),
                subtitle = correctedString(R.string.settings_updates_auto_description),
                icon = Icons.Rounded.Update,
                tone = accentTone(2),
                checked = state.settings.autoCheckUpdates,
                onCheckedChange = viewModel::setAutoCheckUpdates,
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_updates_prerelease),
                subtitle = correctedString(R.string.settings_updates_prerelease_description),
                icon = Icons.Rounded.Science,
                tone = accentTone(5),
                checked = state.settings.includePrerelease,
                onCheckedChange = { on ->
                    // Off is immediate; on goes through a sheet. Asymmetric on
                    // purpose: leaving the test channel needs no warning.
                    if (on) onAskPrerelease() else viewModel.setIncludePrerelease(false)
                },
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_updates_notify),
                subtitle = correctedString(R.string.settings_updates_notify_description),
                icon = Icons.Rounded.NotificationsActive,
                tone = accentTone(1),
                enabled = state.settings.autoCheckUpdates,
                checked = state.settings.notifyNewUpdates && state.settings.autoCheckUpdates,
                onCheckedChange = viewModel::setNotifyNewUpdates,
            )
        }
    }
}

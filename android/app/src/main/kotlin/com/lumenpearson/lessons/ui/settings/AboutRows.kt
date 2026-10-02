package com.lumenpearson.lessons.ui.settings

import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.ui.platform.LocalContext
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.debug.DebugRow

internal fun LazyListScope.aboutRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenDocs: () -> Unit,
) {
    // First on the page, above the switch and the licences. The guide is the
    // only row here somebody arrives *looking* for — everything else on this
    // page is something you find — and a row you have to scroll to is a row a
    // reader concludes does not exist.
    item(key = "docs") {
        SettingsGroup(title = correctedString(R.string.docs_group)) {
            GroupLinkItem(
                title = correctedString(R.string.docs_open),
                subtitle = correctedString(R.string.docs_open_description),
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                tone = accentTone(4),
                onClick = onOpenDocs,
            )
        }
    }
    item(key = "about") {
        SettingsGroup(title = correctedString(R.string.settings_about_group)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_debug),
                subtitle = correctedString(R.string.settings_debug_description),
                icon = Icons.Rounded.BugReport,
                tone = accentTone(2),
                checked = state.settings.debugMode,
                onCheckedChange = viewModel::setDebugMode,
            )
            // Directly under the switch that writes them, and on the page
            // everybody can reach. The reports used to be readable only from
            // the two manager routes — the toolbar's bug button and the
            // management page — while the switch was here, so a reader who was
            // not an administrator could record a crash and never see it.
            DebugRow(
                enabled = state.settings.debugMode,
                onEnabledChange = viewModel::setDebugMode,
            )
        }
    }
    // The last thing on the last page, which is where an about block belongs and
    // where Essentials puts its own. It carries the version, the description and
    // the design credit, so the three rows that used to state those separately
    // are gone rather than repeated above it.
    item(key = "about_card") {
        // No horizontal padding of its own. The list already insets every item
        // by `ScreenPadding`, so this added a second one and the card came out
        // 32 dp narrower on each side than every group above it — which is
        // most of why its two link buttons were breaking «Essentials» into
        // «Essent / ials».
        val context = LocalContext.current
        val revealed = correctedString(R.string.developer_revealed)
        AboutCard(
            serverStatus = state.serverStatus,
            onVersionTapped = {
                viewModel.revealDeveloper()
                Toast.makeText(context, revealed, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

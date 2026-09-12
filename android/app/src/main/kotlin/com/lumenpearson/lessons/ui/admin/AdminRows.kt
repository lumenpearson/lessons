package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.debug.DebugSheet

/**
 * Whether this role is allowed to see the management page at all.
 *
 * A pure function of the role and nothing else, so the same sentence answers
 * three questions that would otherwise drift apart: whether the row appears on
 * the settings landing page, whether the page renders if it is reached by a
 * restored navigation state, and whether the toolbar offers the debug button.
 *
 * `null` — the answer before the server has been asked, and the answer for a
 * phone that is not tied to any Telegram account — is *not* an administrator.
 * Erring the other way would show the page to everyone for the second or two
 * before the first answer arrives, which is the whole of its lifetime for a
 * user who never opens settings again.
 */
fun isClassManager(role: ClassRole?): Boolean =
    role == ClassRole.ADMIN || role == ClassRole.OWNER

/**
 * The management page: what an administrator can do that nobody else can.
 *
 * Hidden rather than disabled. A greyed-out row is an invitation to ask why,
 * and the answer — "because you are not an administrator of this class" — is
 * not something the app should be saying to every pupil who opens its settings.
 * The server refuses the same operations independently, so hiding the page is
 * tidiness rather than security: nothing here is a permission the phone grants
 * itself.
 *
 * @param role the role the server last reported, used for the page's own
 *   heading. The caller has already decided the page may be shown; this is
 *   about telling an owner from an administrator, which the two of them care
 *   about and nobody else does.
 */
fun LazyListScope.adminRows(
    role: ClassRole?,
    debugEnabled: Boolean,
    onDebugEnabledChange: (Boolean) -> Unit,
) {
    item(key = "admin-role") {
        AdminRoleCard(role)
    }

    item(key = "admin-tools") {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = stringResource(R.string.admin_tools_group))
            RoundedCardContainer {
                DebugRow(enabled = debugEnabled, onEnabledChange = onDebugEnabledChange)
            }
        }
    }
}

/** Says which of the two management roles this is, and who else sees the page. */
@Composable
private fun AdminRoleCard(role: ClassRole?) {
    RoundedCardContainer {
        GroupItem(
            title = stringResource(
                if (role == ClassRole.OWNER) R.string.admin_role_owner else R.string.admin_role_admin,
            ),
            subtitle = stringResource(R.string.admin_role_description),
            icon = Icons.Rounded.AdminPanelSettings,
            tone = accentTone(1),
        )
    }
}

/**
 * The way into the debug sheet, now that the toolbar's button is gated.
 *
 * The sheet is hosted here rather than by the shell because this is the only
 * place that can open it: a row that opens a sheet somebody else owns has to
 * hoist a flag up two screens and back down, and the flag is the bug.
 */
@Composable
private fun DebugRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    // Saveable: the sheet survives a rotation, as every other sheet in the app
    // does, and a recreate() from the language picker three pages away.
    var open by rememberSaveable { mutableStateOf(false) }

    GroupItem(
        title = stringResource(R.string.admin_debug),
        subtitle = stringResource(R.string.admin_debug_description),
        icon = Icons.Rounded.BugReport,
        tone = accentTone(5),
        onClick = { open = true },
    )

    if (open) {
        DebugSheet(
            enabled = enabled,
            onEnabledChange = onEnabledChange,
            onDismiss = { open = false },
        )
    }
}

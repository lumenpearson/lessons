package com.lumenpearson.lessons.ui.debug

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone

/**
 * The way into the crash reports, and the sheet it opens.
 *
 * **It is on «О приложении», beside the switch that writes them.** It used to
 * be only two places, and both were the manager's: the bug button beside the
 * toolbar's pill and the management page's own row. So anybody who was not an
 * administrator could turn crash reports *on* — that switch has always been on
 * the «О приложении» page, where everyone can reach it — and then had no way at
 * all to read or send what was written. The report was on their phone, in a
 * folder Android 11 stopped file managers from opening, and the one screen that
 * could show it was behind a role.
 *
 * That is not a theoretical gap. It was found by a crash that happens seconds
 * after this phone is linked to Telegram — which is the moment the manager's
 * routes appear and the moment the app stops staying open long enough to use
 * them.
 *
 * The sheet is hosted by the row rather than by whichever page draws it,
 * because a row that opens somebody else's sheet has to hoist a flag up two
 * screens and back down, and the flag is the bug.
 */
@Composable
internal fun DebugRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    // Saveable: the sheet survives a rotation, as every other sheet in the app
    // does, and a recreate() from the language picker three pages away.
    var open by rememberSaveable { mutableStateOf(false) }

    GroupItem(
        title = correctedString(R.string.debug_reports_open),
        subtitle = correctedString(R.string.debug_reports_open_description),
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

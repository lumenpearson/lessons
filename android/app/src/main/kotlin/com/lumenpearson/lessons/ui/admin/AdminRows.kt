package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.School
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.correctedString
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
 * Every screen it leads to is a bottom sheet rather than a page, because a
 * settings section is a list of rows in somebody else's `LazyColumn` and has no
 * navigation of its own to push onto. The sheets are hosted by [ManagementRows]
 * for the same reason [DebugRow] hosts its own: a row that opens a sheet
 * somebody else owns has to hoist a flag up two screens and back down, and the
 * flag is the bug.
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

    item(key = "admin-manage") {
        ManagementRows(role)
    }

    item(key = "admin-tools") {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = correctedString(R.string.admin_tools_group))
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
            title = correctedString(
                if (role == ClassRole.OWNER) R.string.admin_role_owner else R.string.admin_role_admin,
            ),
            subtitle = correctedString(R.string.admin_role_description),
            icon = Icons.Rounded.AdminPanelSettings,
            tone = accentTone(1),
        )
    }
}

/** The eight sheets the class is run from, and the one thing that closes them all. */
private enum class ManagementScreen { CLASS, SUBJECTS, BELLS, TIMETABLE, DEVICES, LOG, STATS, REQUESTS }

/**
 * The whole of «управление классом», as one row per bot command.
 *
 * One view model for all eight, hoisted here, because they share the answer to
 * the only question that can make every one of them fail at once: whether the
 * linked account still holds the role. The server decides that per request, so
 * it can change between two taps — somebody demoted in the bot while this page
 * is open is demoted here in the same instant — and when it does, the rows go
 * and [ManagementGone] takes their place.
 */
@Composable
private fun ManagementRows(role: ClassRole?) {
    val viewModel: ManagementViewModel = viewModel(factory = ManagementViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Saveable: a sheet survives a rotation, as every other sheet in the app
    // does, and a recreate() from the language picker three pages away.
    var open by rememberSaveable { mutableStateOf<ManagementScreen?>(null) }

    // Nothing else on this page is reachable, so nothing else is drawn: eight
    // rows that each open a sheet which immediately fails is a worse answer
    // than the one sentence that explains why.
    val gone = state.gone
    if (gone != null) {
        // Closed in an effect rather than by assigning during composition: a
        // state this composable also reads must not be written while it is
        // being drawn.
        LaunchedEffect(gone) { open = null }
        ManagementGone(
            failure = gone,
            busy = state.recheckingRole,
            onRecheck = viewModel::recheckRole,
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = correctedString(R.string.admin_manage_group))
        RoundedCardContainer {
            ManagementScreen.entries.forEach { screen ->
                GroupLinkItem(
                    title = correctedString(screen.titleRes),
                    subtitle = correctedString(screen.subtitleRes),
                    icon = screen.icon,
                    tone = accentTone(screen.ordinal),
                    onClick = { open = screen },
                )
            }
        }
    }

    // The load is tied to the sheet being open rather than to the row being
    // drawn: eight reads on every visit to the settings page would be eight
    // requests for a page most visits only scroll past.
    when (open) {
        ManagementScreen.CLASS -> {
            LoadOnOpen(viewModel) { viewModel.loadClass() }
            ClassCardSheet(
                state = state,
                viewModel = viewModel,
                onDismiss = { open = null },
                canDelete = canDeleteClass(role),
            )
        }

        ManagementScreen.SUBJECTS -> {
            LoadOnOpen(viewModel) { viewModel.loadSubjects() }
            SubjectsSheet(state = state, viewModel = viewModel, onDismiss = { open = null })
        }

        ManagementScreen.BELLS -> {
            LoadOnOpen(viewModel) { viewModel.loadBells() }
            BellsSheet(state = state, viewModel = viewModel, onDismiss = { open = null })
        }

        ManagementScreen.TIMETABLE -> {
            LoadOnOpen(viewModel) { viewModel.loadTimetable() }
            TimetableSheet(state = state, viewModel = viewModel, onDismiss = { open = null })
        }

        ManagementScreen.DEVICES -> {
            LoadOnOpen(viewModel) { viewModel.loadDevices() }
            DevicesSheet(state = state, viewModel = viewModel, onDismiss = { open = null })
        }

        ManagementScreen.LOG -> {
            LoadOnOpen(viewModel) { viewModel.loadLog() }
            LogSheet(state = state, viewModel = viewModel, onDismiss = { open = null })
        }

        ManagementScreen.STATS -> {
            LoadOnOpen(viewModel) { viewModel.loadStats() }
            StatsSheet(state = state, viewModel = viewModel, onDismiss = { open = null })
        }

        ManagementScreen.REQUESTS -> {
            LoadOnOpen(viewModel) { viewModel.loadRequests() }
            RequestsSheet(
                state = state,
                viewModel = viewModel,
                role = role,
                onDismiss = { open = null },
            )
        }

        null -> Unit
    }
}

/**
 * One read when a sheet opens, on a clean slate.
 *
 * `LaunchedEffect(Unit)` inside the branch that draws the sheet: the branch
 * exists only while that sheet is up, so this runs on open and again on the
 * next open, which is what a page with no cache wants.
 *
 * The notice and the last write's failure are dropped first. They belong to
 * whatever sheet raised them, and «предмет «Алгебра» удалён» at the top of the
 * devices sheet is a sentence about something that is not on screen.
 */
@Composable
private fun LoadOnOpen(viewModel: ManagementViewModel, load: () -> Unit) {
    LaunchedEffect(Unit) {
        viewModel.consumeNotice()
        viewModel.dismissWriteFailure()
        load()
    }
}

/**
 * The page, after the server has said it is not this user's any more.
 *
 * It says which of the three things happened and offers the only button that
 * can change the answer: asking `/me` again. A page that simply stopped working
 * would leave an administrator pressing rows and being refused with no idea
 * that somebody had changed their role in the bot a minute ago.
 */
@Composable
private fun ManagementGone(
    failure: ManageFailure,
    busy: Boolean,
    onRecheck: () -> Unit,
) {
    val description = when (failure) {
        ManageFailure.SignedOut -> correctedString(R.string.admin_gone_signed_out)
        ManageFailure.NotLinked -> correctedString(R.string.admin_gone_not_linked)
        is ManageFailure.RoleLost -> failure.required
            ?.let { correctedString(R.string.admin_gone_role, it) }
            ?: correctedString(R.string.admin_gone_role_unknown)

        else -> correctedString(R.string.admin_gone_role_unknown)
    }

    EmptyState(
        title = correctedString(R.string.admin_gone_title),
        description = description,
        icon = Icons.Rounded.AdminPanelSettings,
        actionLabel = correctedString(R.string.admin_gone_recheck).takeUnless { busy },
        onActionClick = onRecheck.takeUnless { busy },
    )
}

/** The title, subtitle and hue of one management row. */
private val ManagementScreen.titleRes: Int
    get() = when (this) {
        ManagementScreen.CLASS -> R.string.admin_class_title
        ManagementScreen.SUBJECTS -> R.string.admin_subjects_title
        ManagementScreen.BELLS -> R.string.admin_bells_title
        ManagementScreen.TIMETABLE -> R.string.admin_timetable_title
        ManagementScreen.DEVICES -> R.string.admin_devices_title
        ManagementScreen.LOG -> R.string.admin_log_title
        ManagementScreen.STATS -> R.string.admin_stats_title
        ManagementScreen.REQUESTS -> R.string.admin_requests_title
    }

/** @see titleRes */
private val ManagementScreen.subtitleRes: Int
    get() = when (this) {
        ManagementScreen.CLASS -> R.string.admin_class_description
        ManagementScreen.SUBJECTS -> R.string.admin_subjects_description
        ManagementScreen.BELLS -> R.string.admin_bells_description
        ManagementScreen.TIMETABLE -> R.string.admin_timetable_description
        ManagementScreen.DEVICES -> R.string.admin_devices_description
        ManagementScreen.LOG -> R.string.admin_log_description
        ManagementScreen.STATS -> R.string.admin_stats_description
        ManagementScreen.REQUESTS -> R.string.admin_requests_description
    }

/** @see titleRes */
private val ManagementScreen.icon: ImageVector
    get() = when (this) {
        ManagementScreen.CLASS -> Icons.Rounded.School
        ManagementScreen.SUBJECTS -> Icons.AutoMirrored.Rounded.MenuBook
        ManagementScreen.BELLS -> Icons.Rounded.NotificationsActive
        ManagementScreen.TIMETABLE -> Icons.Rounded.CalendarViewWeek
        ManagementScreen.DEVICES -> Icons.Rounded.PhoneAndroid
        ManagementScreen.LOG -> Icons.AutoMirrored.Rounded.ListAlt
        ManagementScreen.STATS -> Icons.Rounded.QueryStats
        ManagementScreen.REQUESTS -> Icons.Rounded.PersonAdd
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
        title = correctedString(R.string.admin_debug),
        subtitle = correctedString(R.string.admin_debug_description),
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

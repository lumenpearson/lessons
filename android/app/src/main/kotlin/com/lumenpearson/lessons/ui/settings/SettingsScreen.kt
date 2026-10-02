package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.ui.common.ServerUrlSheet
import com.lumenpearson.lessons.ui.developer.DeveloperScreen
import com.lumenpearson.lessons.ui.diary.DiaryAccountPage
import com.lumenpearson.lessons.ui.diary.DiaryScreen
import com.lumenpearson.lessons.ui.diary.DiaryViewModel
import com.lumenpearson.lessons.navigation.isInsecure
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.admin.adminRows
import com.lumenpearson.lessons.ui.admin.isClassManager
import com.lumenpearson.lessons.ui.translate.translationRows

/**
 * The settings landing page: who you are, and where the preferences live.
 *
 * Modelled on the device page of [Essentials](https://github.com/sameerasw/essentials) —
 * a card naming the thing the page is about, then a group of rows that each open
 * a page of their own.
 */
@Composable
fun SettingsRootScreen(
    onOpenSection: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
    mode: ShellMode = ShellMode.CLASS,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // One `/me` on the way in, because the landing page is where the class
    // management row has to decide whether it exists. The refresh is a no-op
    // while one is already in flight, and a failure leaves the previous answer
    // standing, so opening settings repeatedly costs one request and never
    // takes the row away again. Only in a class: without a class token the
    // request answers 401 on every visit, about a row that cannot exist.
    if (mode == ShellMode.CLASS) {
        LaunchedEffect(Unit) { viewModel.refreshDeviceLink() }
    }

    val manager = isClassManager(state.effectiveRole)
    val developer by viewModel.developerRevealed.collectAsStateWithLifecycle()

    SettingsPage(
        modifier = modifier,
        message = state.message?.asText(),
        messageKey = state.message,
        onMessageShown = viewModel::consumeMessage,
    ) {
        item(key = "class-card") {
            if (mode == ShellMode.DIARY) {
                DiaryHeroCard()
            } else {
                ClassHeroCard(
                    className = state.session?.className
                        ?: correctedString(R.string.settings_class_unknown),
                    school = state.session?.school
                        ?: correctedString(R.string.settings_class_no_school),
                )
            }
        }

        item(key = "sections") {
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = correctedString(R.string.settings_sections))
                RoundedCardContainer {
                    val sections = SettingsSection.entries.filter { it.listedOn(mode, manager, developer) }
                    sections.forEach { section ->
                        GroupLinkItem(
                            title = correctedString(section.titleRes),
                            subtitle = correctedString(section.subtitleRes),
                            icon = section.icon,
                            tone = accentTone(section.tone),
                            onClick = { onOpenSection(section) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One section of the preferences, opened from the root.
 *
 * Every branch renders into the same page shape, so a section is a list of rows
 * and nothing else: no screen here owns its own scaffold, padding or snackbar.
 */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    modifier: Modifier = Modifier,
    mode: ShellMode = ShellMode.CLASS,
    onOpenSection: (SettingsSection) -> Unit = {},
    onOpenDocs: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    // The developer mode is a page of its own too, with a view model of its
    // own: records that grow while it is open, and nothing that is a
    // preference (#237).
    if (section == SettingsSection.DEVELOPER) {
        DeveloperScreen(modifier = modifier)
        return
    }

    // The diary is a whole screen of its own rather than a list of rows: it has
    // a sign-in, a week of a timetable and a register in it, and none of that
    // is a preference. It still arrives as a section so that it inherits the
    // shell's title, its back gesture and the slide that carries it in.
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (section == SettingsSection.DIARY) {
        // On the diary home the diary is already on screen behind this page;
        // what the section adds is the account and the way out of it.
        if (mode == ShellMode.DIARY) {
            DiaryAccountPage(modifier = modifier)
        } else {
            DiaryScreen(insecureServer = isInsecure(state.settings.baseUrl), modifier = modifier)
        }
        return
    }
    val submitState by viewModel.translationSubmit.collectAsStateWithLifecycle()
    var showServerSheet by rememberSaveable { mutableStateOf(false) }
    var showSignOutSheet by rememberSaveable { mutableStateOf(false) }
    var showUnlinkSheet by rememberSaveable { mutableStateOf(false) }
    var showAddClassSheet by rememberSaveable { mutableStateOf(false) }
    // The id rather than the Session: this survives a configuration change, and
    // a Session is not parcelable. It is resolved against the live list below,
    // so a class that stops existing while the sheet is up closes it instead of
    // asking about a name nobody is in any more.
    var leavingClassId by rememberSaveable { mutableStateOf<Long?>(null) }

    // The link is a fact about the token that only the server holds, so the
    // class page asks on every visit. Keyed on the section: the same view
    // model serves every page, and a visit to «Оформление» is not a visit here.
    //
    // And keyed on the class as well, because there is a token per class and
    // the switch happens on this very page: without it, switching to a class
    // this phone is only a reader in went on offering the editor's rows the
    // previous class had earned.
    if (section == SettingsSection.ACCOUNT) {
        LaunchedEffect(state.session?.classId) { viewModel.refreshDeviceLink() }
    }

    if (showUnlinkSheet) {
        UnlinkSheet(
            onDismiss = { showUnlinkSheet = false },
            onConfirm = {
                showUnlinkSheet = false
                viewModel.unlinkDevice()
            },
        )
    }
    val sheets = rememberSupportSheets()

    SupportSheets(sheets = sheets, state = state, viewModel = viewModel)

    if (showServerSheet) {
        ServerUrlSheet(
            initialUrl = state.settings.baseUrl,
            onDismiss = { showServerSheet = false },
            onConfirm = { url ->
                viewModel.setBaseUrl(url)
                showServerSheet = false
            },
        )
    }

    if (showSignOutSheet) {
        SignOutSheet(
            className = state.session?.className,
            everyClass = state.sessions.size > 1,
            onDismiss = { showSignOutSheet = false },
            onConfirm = {
                showSignOutSheet = false
                viewModel.signOut()
            },
        )
    }

    if (showAddClassSheet) {
        AddClassSheet(onDismiss = { showAddClassSheet = false })
    }

    // Resolved against the live list: the class can stop being one this phone
    // is in while the sheet is up — the confirmation itself does exactly that —
    // and asking about a name nobody is in any more is worse than closing.
    val leaving = leavingClassId?.let { id -> state.sessions.firstOrNull { it.classId == id } }
    LaunchedEffect(leaving, leavingClassId) {
        if (leavingClassId != null && leaving == null) leavingClassId = null
    }
    if (leaving != null) {
        LeaveClassSheet(
            className = leaving.className,
            onDismiss = { leavingClassId = null },
            onConfirm = {
                leavingClassId = null
                viewModel.leaveClass(leaving.classId)
            },
        )
    }

    SettingsPage(
        modifier = modifier,
        message = state.message?.asText(),
        messageKey = state.message,
        onMessageShown = viewModel::consumeMessage,
    ) {
        item(key = "header") {
            ScreenHeader(
                title = correctedString(section.titleRes),
                subtitle = correctedString(section.subtitleRes),
            )
        }

        when (section) {
            SettingsSection.APPEARANCE -> appearanceRows(state, viewModel)
            SettingsSection.FEEL -> feelRows(state, viewModel, tabs = mode != ShellMode.DIARY)
            SettingsSection.CONTENT -> contentRows(state, viewModel)
            SettingsSection.ALERTS -> notificationRows(state, viewModel, onOpenSection)
            SettingsSection.SYNC -> syncRows(state, viewModel, classSync = mode != ShellMode.DIARY) {
                showServerSheet = true
            }
            SettingsSection.ACCOUNT -> {
                classRows(
                    state = state,
                    onSelectClass = viewModel::selectClass,
                    onAddClass = { showAddClassSheet = true },
                    onLeaveClass = { leavingClassId = it.classId },
                    onSignOut = { showSignOutSheet = true },
                )
                telegramLinkRows(
                    state = state.deviceLink,
                    onRefresh = viewModel::refreshDeviceLink,
                    onUnlink = { showUnlinkSheet = true },
                )
            }
            SettingsSection.UPDATES -> updateRows(
                state = state,
                viewModel = viewModel,
                onShowRelease = viewModel::showReleaseSheet,
                onAskPrerelease = { sheets.prerelease = true },
            )
            SettingsSection.ABOUT -> {
                aboutRows(state, viewModel, onOpenDocs)
                supportRows(
                    state = state,
                    viewModel = viewModel,
                    onReportBug = { sheets.bugReport = true },
                    onShowLicenses = { sheets.licenses = true },
                )
                // Last on the page about the app itself, below the licences and
                // the bug report: correcting a translation is the same kind of
                // errand as reporting a typo, and a mode that changes what a
                // long press does everywhere should not sit where somebody
                // reaches by accident.
                translationRows(
                    account = state.github,
                    canSignIn = state.githubConfigured,
                    onSignIn = {
                        sheets.signIn = true
                        viewModel.signInWithGithub()
                    },
                    submit = submitState,
                    onSubmit = viewModel::submitCorrections,
                    onAcknowledge = viewModel::acknowledgeSubmission,
                )
            }
            SettingsSection.ADMIN -> adminRows(
                role = state.effectiveRole,
                debugEnabled = state.settings.debugMode,
                onDebugEnabledChange = viewModel::setDebugMode,
            )
            SettingsSection.PERMISSIONS -> permissionRows()
            // Handled above, before this page's scaffold exists.
            SettingsSection.DIARY, SettingsSection.DEVELOPER -> Unit
        }
    }
}

/**
 * The shape every settings page shares.
 *
 * No `Scaffold` and no top app bar: the page starts at the top of the window so
 * that its first rows can scroll under the status bar and be softened there, and
 * its name is in the pill at the bottom. The status-bar inset is therefore part
 * of the list's content padding rather than padding around the list — padding
 * around it would stop the list short of the very bar it scrolls behind.
 */
@Composable
private fun SettingsPage(
    modifier: Modifier = Modifier,
    message: String? = null,
    messageKey: Any? = null,
    onMessageShown: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    ReportScrollOffset(listState)

    if (message != null) {
        LaunchedEffect(messageKey) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .appScrollMotionBlur(listState),
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = statusBarSpace() + 8.dp,
                bottom = LocalBottomBarSpace.current,
            ),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
            content = content,
        )

        // Lifted clear of the floating toolbar: the pill sits a few dp above the
        // navigation bar and is drawn after this, so an unlifted snackbar — the
        // app's only error feedback — appears underneath it and is never read.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalBottomBarSpace.current),
        )
    }
}

/** A labelled group; the one place the label-to-group spacing is decided. */
@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        RoundedCardContainer(content = content)
    }
}

/**
 * The card at the top of the root page on the diary home: the pupil and the
 * school, from the diary view model the home already holds (the activity's).
 */
@Composable
private fun DiaryHeroCard(viewModel: DiaryViewModel = viewModel(factory = DiaryViewModel.Factory)) {
    val diary by viewModel.uiState.collectAsStateWithLifecycle()
    val student = diary.student
    ClassHeroCard(
        className = student?.fullName ?: correctedString(R.string.diary_title),
        school = student?.let { listOfNotNull(it.className, it.school).joinToString(" · ") }
            ?.ifBlank { null }
            ?: diary.signInTarget.schoolName
            ?: correctedString(R.string.settings_class_no_school),
    )
}

/** The card at the top of the root page, naming the class the app is signed into. */
@Composable
private fun ClassHeroCard(
    className: String,
    school: String,
    modifier: Modifier = Modifier,
) {
    RoundedCardContainer(modifier = modifier) {
        GroupRow {
            AccentIconTile(icon = Icons.Rounded.School, tone = accentTone(1))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = className,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = school,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Confirmation before signing out.
 *
 * Cheap insurance: the code needed to get back in lives on a teacher's list, not
 * in the pupil's head. A sheet rather than a dialog, so that the one destructive
 * action in the app arrives from the same edge as everything else.
 */
@Composable
private fun SignOutSheet(
    className: String?,
    everyClass: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = correctedString(
            if (everyClass) R.string.settings_sign_out_all_title else R.string.settings_sign_out_title,
        ),
    ) {
        Text(
            // Naming the one class is only honest while there is one. With
            // several, this button takes them all, and a sentence about 7«А»
            // over a button that also drops 9«Б» is the kind of wrong that is
            // only discovered afterwards.
            text = when {
                everyClass -> correctedString(R.string.settings_sign_out_all_message)
                className != null -> correctedString(R.string.settings_sign_out_message, className)
                else -> correctedString(R.string.settings_sign_out_message_generic)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = correctedString(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(text = correctedString(R.string.settings_sign_out))
            }
        }
    }
}

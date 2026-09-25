package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Grade
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.LessonsDialog
import com.lumenpearson.lessons.core.designsystem.component.LessonsSuccessDialog
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SegmentedPicker
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import androidx.compose.ui.draw.clip
import com.lumenpearson.lessons.ui.common.syncedAtLabel

/**
 * The diary: a page of the settings tree on a phone in a class, and the home
 * itself on a phone in none.
 *
 * In a class it is a section reached from the settings root, not a fourth tab:
 * the tabs are the class timetable, which every install has the moment it joins
 * a class, and a tab for a second account in a foreign service would put a
 * sign-in wall in the bottom bar of everybody who does not have one. `HomeTab`
 * also lives in `:core:model`, where the widget and the "default tab" preference
 * read it, so a fourth entry there would appear in two more places that have
 * nothing to do with a diary.
 *
 * On a phone whose only account is the diary ([asHome]), there is no timetable
 * to be a tab beside, and the diary is what the app is: the shell draws this
 * page where the tabs would be and carries the diary's own two halves in its
 * toolbar, so the in-page picker goes; and the sign-out moves to the account
 * page in settings, next to the rest of what the account is, because on the
 * home it would be one tap from the first screen somebody sees.
 *
 * The page is a `LazyColumn` of the same shape as every settings section — same
 * padding, same status-bar inset, same scroll report for the top fade — rather
 * than a screen with a scaffold of its own.
 *
 * @param asHome drawn as the home of the diary mode rather than as a section.
 */
@Composable
fun DiaryScreen(
    /** Whether the server address is plain `http://`; see `DiarySignInScreen`. */
    insecureServer: Boolean = false,
    modifier: Modifier = Modifier,
    asHome: Boolean = false,
    viewModel: DiaryViewModel = viewModel(factory = DiaryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    // Held in the state holder rather than here, because it carries a typed
    // value and a busy flag and has to survive the reload a save triggers —
    // the same reason `signInOutcome` lives there.
    state.editing?.let { corrections ->
        DiaryEditSheet(
            corrections = corrections,
            saving = state.savingEdit,
            error = state.editError?.asText(),
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::saveEdit,
            onReset = viewModel::resetEdit,
        )
    }

    if (confirmSignOut) {
        DiarySignOutSheet(
            leavesTheApp = asHome,
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut()
            },
        )
    }

    // Above the `when` below rather than inside the sign-in branch, because the
    // success case has to survive the branch it was produced in: the moment the
    // session lands, `state.session` stops being null and the form is gone. A
    // dialog hosted inside it would be taken off screen in the same frame that
    // gave it something to say.
    DiarySignInOutcomeDialog(
        outcome = state.signInOutcome,
        onDismiss = viewModel::consumeSignInOutcome,
    )

    when {
        // Never the sign-in form before the stored session has been read:
        // that frame would be a login page shown to somebody already signed in.
        !state.ready -> DiaryPage(modifier) {
            item(key = "loading") { SkeletonGroup(rows = 4) }
        }

        // Choosing which diary, before a form for it: a phone whose class named
        // none, or somebody who is not in Petersburg (r11, gap 2).
        state.picking && state.session == null -> DiaryPickerPage(
            onPicked = viewModel::choose,
            onCancel = viewModel::cancelPicking,
            modifier = modifier,
        )

        state.session == null || state.reauth -> DiarySignInScreen(
            reauth = state.reauth,
            knownLogin = state.knownLogin,
            insecureServer = insecureServer,
            busy = state.signingIn,
            failed = state.signInError != null,
            onSignIn = viewModel::signIn,
            onEdited = viewModel::clearSignInError,
            place = state.place,
            schoolName = state.signInTarget.schoolName,
            onChangeDiary = viewModel::startPicking,
            modifier = modifier,
        )

        else -> DiaryPage(modifier) {
            item(key = "header") {
                ScreenHeader(
                    title = state.student?.fullName ?: correctedString(R.string.diary_title),
                    subtitle = state.student?.let { student ->
                        listOfNotNull(student.className, student.school).joinToString(" · ")
                            .ifBlank { null }
                    } ?: correctedString(
                        R.string.diary_signed_in_as,
                        state.session?.login.orEmpty(),
                    ),
                )
            }

            // The rows below are what the phone saved, not what the diary said
            // just now — said once, above them, with when they were saved, in
            // the diary's zone rather than the phone's.
            state.savedAt?.let { savedAt ->
                item(key = "saved-at") {
                    DiarySavedAtNote(
                        text = correctedString(
                            R.string.diary_offline_saved_at,
                            syncedAtLabel(savedAt.toEpochMilli(), state.zone),
                        ),
                    )
                }
            }

            if (state.showStudentPicker) {
                item(key = "students") {
                    DiaryStudentPicker(
                        students = state.students,
                        selectedId = state.selectedStudentId,
                        onSelect = viewModel::selectStudent,
                    )
                }
            }

            // On the home the toolbar carries the two halves; a second switch
            // for the same thing under the header would be one too many.
            if (!asHome) item(key = "tabs") {
                SegmentedPicker(
                    items = DiaryTab.entries,
                    selectedItem = state.tab,
                    onItemSelected = viewModel::setTab,
                    labelProvider = { tab -> correctedString(tab.labelRes()) },
                    containerColor = MaterialTheme.colorScheme.rowContainer,
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.clip(LessonsShapeTokens.Group),
                )
            }

            when {
                state.studentsLoading -> item(key = "students-loading") { SkeletonGroup(rows = 3) }

                state.studentsError != null -> item(key = "students-error") {
                    DiaryFailureCard(state.studentsError, viewModel::retry)
                }

                state.students.isEmpty() -> item(key = "students-empty") {
                    EmptyState(
                        title = correctedString(R.string.diary_students_empty_title),
                        description = correctedString(R.string.diary_students_empty_text),
                    )
                }

                state.tab == DiaryTab.SCHEDULE -> diarySchedule(state, viewModel)

                else -> diaryGrades(state, viewModel)
            }

            if (!asHome) item(key = "sign-out") {
                RoundedCardContainer {
                    GroupItem(
                        title = correctedString(R.string.diary_sign_out),
                        tone = errorTone(),
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        enabled = !state.signingOut,
                        onClick = { confirmSignOut = true },
                    )
                }
            }
        }
    }
}

/**
 * The page shape the section shares with the settings tree.
 *
 * Copied in spirit rather than reused because `SettingsPage` is private to the
 * settings package and carries a snackbar host this page has nothing to put in:
 * every failure here is a card in the list, next to the thing that failed,
 * which is where a retry button belongs.
 */
@Composable
internal fun DiaryPage(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    ReportScrollOffset(listState)

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
    }
}

/**
 * The children of the account, as a row of chips.
 *
 * Absent entirely for the single-child account, which is most of them: a picker
 * with one option is a control that can only ever be pressed to no effect.
 */
@Composable
internal fun DiaryStudentPicker(
    students: List<DiaryStudent>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = correctedString(R.string.diary_students_section))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            students.forEach { student ->
                PillChip(
                    text = student.shortName,
                    selected = student.id == selectedId,
                    onClick = { onSelect(student.id) },
                )
            }
        }
    }
}

/** A failure, said in words, with the one button that can do anything about it. */
@Composable
internal fun DiaryFailureCard(
    failure: DiaryFailure?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failure == null) return
    // Only the answers worth pressing a button about get one — the data layer's
    // own verdict (`DiarySignInProblem.action`). A diary switched off on the
    // server, or a request this app got wrong, answers the same way twice.
    val retry = failure.asProblem()?.offersRetry == true
    EmptyState(
        title = correctedString(R.string.diary_failure_title),
        description = failure.asText(),
        modifier = modifier,
        actionLabel = correctedString(R.string.diary_retry).takeIf { retry },
        onActionClick = onRetry.takeIf { retry },
    )
}

/**
 * Every failure a diary read can end in, as one sentence each.
 *
 * Three are about what this app asked — a pupil the account does not have, a
 * range the server refuses, a correction it will not file — and keep their own
 * sentences. Everything else is a way of not getting into the diary, and is
 * said by `DiaryProblemText`, the one mapping there is: a 429 as too many
 * attempts, a diary switched off on the server as switched off, a refused
 * server address as the diary refusing our server (#153).
 */
@Composable
internal fun DiaryFailure.asText(): String = when (this) {
    DiaryFailure.UnknownStudent -> correctedString(R.string.diary_error_student)
    DiaryFailure.BadRange -> correctedString(R.string.diary_error_range)
    // The server refusing a correction it could never apply. Its own message,
    // because "не получилось: 422" is not something to put in front of anybody.
    DiaryFailure.Rejected -> correctedString(R.string.diary_error_rejected)
    else -> DiarySignInProblem.of(this).asText()
}

/** The line above the rows when they are what the phone saved. */
@Composable
private fun DiarySavedAtNote(text: String) {
    RoundedCardContainer {
        GroupRow {
            AccentIconTile(icon = Icons.Rounded.CloudOff, tone = accentTone(3))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The pop-up that says how the last sign-in attempt went.
 *
 * Both outcomes, which is the point. The diary used to answer a wrong password
 * with a line of red text below two fields and above a button — off the bottom
 * of the screen on a phone with the keyboard up — and a correct one with
 * nothing at all: the form simply went away, which is also what happens when
 * the screen is left. Neither told the one thing the person wanted to know.
 *
 * `null` draws nothing, so the caller can hold this at the top of the screen
 * and let the state decide.
 */
@Composable
private fun DiarySignInOutcomeDialog(
    outcome: DiarySignInOutcome?,
    onDismiss: () -> Unit,
) {
    when (outcome) {
        null -> Unit

        is DiarySignInOutcome.Succeeded -> LessonsSuccessDialog(
            title = correctedString(R.string.diary_sign_in_ok_title),
            // The login is echoed back because the commonest wrong answer to
            // «правильно ли я ввёл?» is a typo in an address that was accepted.
            message = correctedString(R.string.diary_sign_in_ok_message, outcome.login),
            confirmLabel = correctedString(R.string.diary_dialog_dismiss),
            onDismiss = onDismiss,
        )

        is DiarySignInOutcome.Failed -> LessonsDialog(
            title = correctedString(R.string.diary_sign_in_failed_title),
            // The one mapping, which knows a refused password from a diary
            // that is down, a throttle and a server that is switched off.
            message = outcome.problem.asText(),
            confirmLabel = correctedString(R.string.diary_dialog_dismiss),
            onDismiss = onDismiss,
        )
    }
}

/** The label of a tab — in the segmented picker, and in the toolbar on the home. */
internal fun DiaryTab.labelRes(): Int = when (this) {
    DiaryTab.SCHEDULE -> R.string.diary_tab_schedule
    DiaryTab.GRADES -> R.string.diary_tab_grades
}

/** The glyph of a tab in the toolbar, on the diary home. */
internal val DiaryTab.icon: ImageVector
    get() = when (this) {
        DiaryTab.SCHEDULE -> Icons.Rounded.CalendarViewWeek
        DiaryTab.GRADES -> Icons.Rounded.Grade
    }

/**
 * The confirmation, which exists to say what signing out does *not* do — and,
 * when the diary is the phone's only account ([leavesTheApp]), what it does:
 * the phone goes back to the start, and what was saved on it goes.
 *
 * In a class the two accounts are separate and nothing on screen says so until
 * the moment somebody is about to leave one of them.
 */
@Composable
internal fun DiarySignOutSheet(
    leavesTheApp: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = correctedString(R.string.diary_sign_out_title),
    ) {
        Text(
            text = correctedString(
                if (leavesTheApp) R.string.diary_sign_out_home_text else R.string.diary_sign_out_message,
            ),
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
                Text(text = correctedString(R.string.diary_sign_out))
            }
        }
    }
}

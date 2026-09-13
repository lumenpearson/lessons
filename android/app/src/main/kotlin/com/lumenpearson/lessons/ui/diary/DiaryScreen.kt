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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
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
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import androidx.compose.ui.draw.clip

/**
 * The Petersburg diary, as one page of the settings tree.
 *
 * Why here and not as a fourth tab: the tabs are the class timetable, which
 * every install has the moment it joins a class. The diary is a second account
 * in a foreign service that most users of this app will never have, and a tab
 * for it would put a sign-in wall in the bottom bar of everybody who does not —
 * permanently, because the toolbar does not hide destinations. `HomeTab` also
 * lives in `:core:model`, where the widget and the "default tab" preference
 * read it, so a fourth entry there would appear in two more places that have
 * nothing to do with a diary. A section reached from the settings root costs
 * one row on a page people already visit, and it comes with the shell's title,
 * its back gesture and its slide for free.
 *
 * The page is a `LazyColumn` of the same shape as every settings section — same
 * padding, same status-bar inset, same scroll report for the top fade — rather
 * than a screen with a scaffold of its own.
 */
@Composable
fun DiaryScreen(
    modifier: Modifier = Modifier,
    viewModel: DiaryViewModel = viewModel(factory = DiaryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    if (confirmSignOut) {
        DiarySignOutSheet(
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

        state.session == null || state.reauth -> DiarySignInScreen(
            reauth = state.reauth,
            knownLogin = state.session?.login.orEmpty(),
            busy = state.signingIn,
            failed = state.signInError != null,
            onSignIn = viewModel::signIn,
            onEdited = viewModel::clearSignInError,
            modifier = modifier,
        )

        else -> DiaryPage(modifier) {
            item(key = "header") {
                ScreenHeader(
                    title = state.student?.fullName ?: stringResource(R.string.diary_title),
                    subtitle = state.student?.let { student ->
                        listOfNotNull(student.className, student.school).joinToString(" · ")
                            .ifBlank { null }
                    } ?: stringResource(
                        R.string.diary_signed_in_as,
                        state.session?.login.orEmpty(),
                    ),
                )
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

            item(key = "tabs") {
                SegmentedPicker(
                    items = DiaryTab.entries,
                    selectedItem = state.tab,
                    onItemSelected = viewModel::setTab,
                    labelProvider = { tab -> stringResource(tab.labelRes()) },
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
                        title = stringResource(R.string.diary_students_empty_title),
                        description = stringResource(R.string.diary_students_empty_text),
                    )
                }

                state.tab == DiaryTab.SCHEDULE -> diarySchedule(state, viewModel)

                else -> diaryGrades(state, viewModel)
            }

            item(key = "sign-out") {
                RoundedCardContainer {
                    GroupItem(
                        title = stringResource(R.string.diary_sign_out),
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
private fun DiaryPage(
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
private fun DiaryStudentPicker(
    students: List<DiaryStudent>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.diary_students_section))
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
    EmptyState(
        title = stringResource(R.string.diary_failure_title),
        description = failure.asText(),
        modifier = modifier,
        // Only the answers worth pressing a button about get one. A 502 means
        // the server has to be fixed and a 422 means this app asked wrongly;
        // pressing "повторить" would produce the same answer, twice.
        actionLabel = stringResource(R.string.diary_retry).takeIf { failure.isRetryable },
        onActionClick = onRetry.takeIf { failure.isRetryable },
    )
}

/** Whether pressing a button again could plausibly give a different answer. */
private val DiaryFailure.isRetryable: Boolean
    get() = this is DiaryFailure.Unavailable ||
        this is DiaryFailure.Offline ||
        this is DiaryFailure.Unexpected

/**
 * Every failure this section can show, as one sentence each.
 *
 * [DiaryFailure.SignInRequired] reads as "sign in again" here; on the sign-in
 * form itself the same failure means the credentials were refused, which is
 * why that screen overrides this one case and no other.
 */
@Composable
internal fun DiaryFailure.asText(): String = when (this) {
    DiaryFailure.SignInRequired -> stringResource(R.string.diary_error_signed_out)
    // Shown only if it ever leaks into a card: the state holder turns it into
    // the password prompt long before a screen could render it.
    DiaryFailure.ReauthRequired -> stringResource(R.string.diary_reauth_title)
    DiaryFailure.UnknownStudent -> stringResource(R.string.diary_error_student)
    DiaryFailure.BadRange -> stringResource(R.string.diary_error_range)
    DiaryFailure.Unreadable -> stringResource(R.string.diary_error_unreadable)
    DiaryFailure.Unavailable -> stringResource(R.string.diary_error_unavailable)
    is DiaryFailure.Offline -> stringResource(R.string.diary_error_offline)
    is DiaryFailure.Unexpected -> stringResource(
        R.string.diary_error_unknown,
        reason.message?.takeIf { it.isNotBlank() } ?: message.orEmpty(),
    )
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
            title = stringResource(R.string.diary_sign_in_ok_title),
            // The login is echoed back because the commonest wrong answer to
            // «правильно ли я ввёл?» is a typo in an address that was accepted.
            message = stringResource(R.string.diary_sign_in_ok_message, outcome.login),
            confirmLabel = stringResource(R.string.diary_dialog_dismiss),
            onDismiss = onDismiss,
        )

        is DiarySignInOutcome.Failed -> LessonsDialog(
            title = stringResource(R.string.diary_sign_in_failed_title),
            // `asSignInText`, not `asText`: on this one call a 401 means "the
            // diary refused these credentials", and everywhere else it means
            // "your session is gone". Saying the second here would send
            // somebody to sign in again on the screen they are already on.
            message = outcome.failure.asSignInText(),
            confirmLabel = stringResource(R.string.diary_dialog_dismiss),
            onDismiss = onDismiss,
        )
    }
}

/** The label of a tab in the segmented picker. */
private fun DiaryTab.labelRes(): Int = when (this) {
    DiaryTab.SCHEDULE -> R.string.diary_tab_schedule
    DiaryTab.GRADES -> R.string.diary_tab_grades
}

/**
 * The confirmation, which exists to say what signing out does *not* do.
 *
 * The two accounts are separate and nothing on screen says so until the moment
 * somebody is about to leave one of them.
 */
@Composable
private fun DiarySignOutSheet(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.diary_sign_out_title),
    ) {
        Text(
            text = stringResource(R.string.diary_sign_out_message),
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
                Text(text = stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(text = stringResource(R.string.diary_sign_out))
            }
        }
    }
}

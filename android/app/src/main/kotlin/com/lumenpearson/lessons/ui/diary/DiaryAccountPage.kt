package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.ui.settings.AddClassSheet

/**
 * Settings → «Дневник» on a phone whose only account is the diary: the pupil,
 * the account, the way to a class, and the way out.
 *
 * Not a second `DiaryScreen`. On this phone the diary already *is* the home,
 * and a section that drew it again would be the same week twice, one of them
 * behind a back gesture. What the home cannot hold is what this page is for —
 * which account this is and where it signs in, and «Выйти из дневника», which
 * on the home would be one tap from the first screen somebody sees and here is
 * where the other account pages keep their exits.
 *
 * The class row is the whole of the path from here to everything the diary
 * mode does not have — the class timetable, the widget, the bell reminders —
 * and it is offered, not pressed: a class code comes from a school that has set
 * one up, which most families reaching this page have not been given.
 *
 * It reads the view model the home uses (the activity's), so the pupil chosen
 * here is the pupil the home shows.
 */
@Composable
fun DiaryAccountPage(
    modifier: Modifier = Modifier,
    viewModel: DiaryViewModel = viewModel(factory = DiaryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var addingClass by rememberSaveable { mutableStateOf(false) }

    if (confirmSignOut) {
        DiarySignOutSheet(
            leavesTheApp = true,
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut()
            },
        )
    }
    if (addingClass) {
        AddClassSheet(onDismiss = { addingClass = false })
    }

    val target = state.session?.target ?: state.signInTarget
    val place = state.place

    DiaryPage(modifier) {
        item(key = "header") {
            ScreenHeader(
                title = state.student?.fullName ?: correctedString(R.string.diary_title),
                subtitle = state.student?.let { student ->
                    listOfNotNull(student.className, student.school).joinToString(" · ").ifBlank { null }
                } ?: correctedString(R.string.diary_signed_in_as, target.login),
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

        item(key = "account") {
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = correctedString(R.string.diary_title))
                RoundedCardContainer {
                    place?.systemName()?.let { system ->
                        GroupItem(
                            title = correctedString(R.string.diary_account_system),
                            subtitle = system,
                            icon = Icons.AutoMirrored.Rounded.MenuBook,
                            tone = accentTone(4),
                        )
                    }
                    place?.regionName()?.let { region ->
                        GroupItem(
                            title = correctedString(R.string.diary_account_region),
                            subtitle = region,
                            icon = Icons.Rounded.Map,
                            tone = accentTone(0),
                        )
                    }
                    target.schoolName?.takeIf { it.isNotBlank() }?.let { school ->
                        GroupItem(
                            title = correctedString(R.string.diary_account_school),
                            subtitle = school,
                            icon = Icons.Rounded.School,
                            tone = accentTone(1),
                        )
                    }
                    target.login.takeIf { it.isNotBlank() }?.let { login ->
                        GroupItem(
                            title = correctedString(R.string.diary_login_label),
                            subtitle = login,
                            icon = Icons.Rounded.Person,
                            tone = accentTone(2),
                        )
                    }
                }
            }
        }

        item(key = "class") {
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = correctedString(R.string.settings_class))
                RoundedCardContainer {
                    GroupLinkItem(
                        title = correctedString(R.string.diary_account_join_class),
                        icon = Icons.Rounded.GroupAdd,
                        tone = accentTone(3),
                        onClick = { addingClass = true },
                    )
                    GroupRow {
                        Text(
                            text = correctedString(R.string.diary_account_class_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item(key = "sign-out") {
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

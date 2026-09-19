package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone

/** What the subjects sheet is doing: the list, or one row's form. */
internal sealed interface SubjectsMode {
    data object List : SubjectsMode
    data object Add : SubjectsMode
    data class Edit(val subject: ManagedSubject) : SubjectsMode
    data class Delete(val subject: ManagedSubject) : SubjectsMode
}

/**
 * Which of the sheet's screens is up — the half of [SubjectsMode] that can be
 * saved; [bellsModeOf] says why the other half is resolved rather than saved.
 */
internal enum class SubjectsScreen { LIST, ADD, EDIT, DELETE }

/**
 * The mode [screen] and [id] stand for, against the subjects actually on hand.
 *
 * A screen that needs a subject and has none falls back to the list: either the
 * list has not arrived yet, or the subject was deleted from the bot while this
 * sheet was in the background. The editor is the reason this matters more here
 * than anywhere else — it draws four boxes filled in from the subject, and with
 * nothing behind them «Сохранить» would offer to rename something that is no
 * longer there.
 */
internal fun subjectsModeOf(
    screen: SubjectsScreen,
    id: Long?,
    subjects: List<ManagedSubject>?,
): SubjectsMode {
    // Adding needs no subject, so it must not be refused for want of one.
    if (screen == SubjectsScreen.ADD) return SubjectsMode.Add
    val subject = subjects?.firstOrNull { it.id == id } ?: return SubjectsMode.List
    return when (screen) {
        SubjectsScreen.EDIT -> SubjectsMode.Edit(subject)
        SubjectsScreen.DELETE -> SubjectsMode.Delete(subject)
        SubjectsScreen.LIST, SubjectsScreen.ADD -> SubjectsMode.List
    }
}

/**
 * «📚 Предметы»: the dictionary that keeps one subject from being three.
 *
 * The list and the form share the sheet for the same reason the class card does
 * — one errand — and the edit form always shows all four fields, which is what
 * makes an empty box mean «убрать» rather than «не трогал».
 */
@Composable
fun SubjectsSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The screen and the id, not the mode: a rotation — and, below API 33, the
    // `recreate()` the language picker three pages away performs — rebuilds this
    // composable from nothing, and a `remember` here threw away four boxes the
    // reader had just filled in, `rememberSaveable` in the editor and all. See
    // [subjectsModeOf].
    var screen by rememberSaveable { mutableStateOf(SubjectsScreen.LIST) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val subjects = state.subjects.value
    val mode = remember(screen, openId, subjects) { subjectsModeOf(screen, openId, subjects) }

    fun show(next: SubjectsScreen, subject: ManagedSubject? = null) {
        screen = next
        openId = subject?.id
    }

    ManagementSheet(
        title = correctedString(R.string.admin_subjects_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when (val current = mode) {
            SubjectsMode.Add -> SubjectEditor(
                subject = null,
                busy = state.working,
                failure = state.writeFailure,
                onCancel = { show(SubjectsScreen.LIST) },
                onSave = { form ->
                    viewModel.addSubject(form)
                    show(SubjectsScreen.LIST)
                },
            )

            is SubjectsMode.Edit -> SubjectEditor(
                subject = current.subject,
                busy = state.working,
                failure = state.writeFailure,
                onCancel = { show(SubjectsScreen.LIST) },
                onSave = { form ->
                    viewModel.saveSubject(current.subject, form)
                    show(SubjectsScreen.LIST)
                },
                onDelete = { show(SubjectsScreen.DELETE, current.subject) },
            )

            is SubjectsMode.Delete -> {
                SheetSection(
                    title = correctedString(
                        R.string.admin_subject_delete_title,
                        current.subject.name,
                    ),
                )
                SheetNote(text = correctedString(R.string.admin_subject_delete_message))
                SheetFailure(failure = state.writeFailure)
                SheetButtons(
                    confirmLabel = correctedString(R.string.admin_subject_delete),
                    onConfirm = {
                        viewModel.deleteSubject(current.subject)
                        show(SubjectsScreen.LIST)
                    },
                    onCancel = { show(SubjectsScreen.LIST) },
                    busy = state.working,
                    destructive = true,
                )
            }

            SubjectsMode.List -> {
                // Only the notices this sheet caused are drawn here; a rename's
                // «строк обновлено» is the whole reason the notice exists.
                SheetNotice(text = state.notice?.takeIf { it.isAboutSubjects }?.asText())
                SheetFailure(failure = state.writeFailure)
                when {
                    subjects == null && state.subjects.loading -> SkeletonGroup(
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        rows = 4,
                    )

                    subjects == null -> ManagementFailureCard(
                        failure = state.subjects.failure,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        onRetry = viewModel::loadSubjects,
                    )

                    subjects.isEmpty() -> EmptyState(
                        title = correctedString(R.string.admin_subjects_empty_title),
                        description = correctedString(R.string.admin_subjects_empty_text),
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )

                    else -> RoundedCardContainer(
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    ) {
                        subjects.forEach { subject ->
                            GroupItem(
                                title = subject.name,
                                subtitle = subtitleOf(subject),
                                icon = Icons.AutoMirrored.Rounded.MenuBook,
                                // The subject's own colour, through the same
                                // function the timetable draws it with, so the
                                // dictionary and the lessons agree on screen.
                                tone = subjectTone(subject.name, subject.color),
                                onClick = { show(SubjectsScreen.EDIT, subject) },
                            )
                        }
                    }
                }
                GroupActionItem(
                    label = correctedString(R.string.admin_subject_add),
                    icon = Icons.Rounded.Add,
                    onClick = { show(SubjectsScreen.ADD) },
                    busy = state.working,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }
    }
}

/** "Алг · Иванова А. П." — whatever of the two the subject actually has. */
private fun subtitleOf(subject: ManagedSubject): String? =
    listOfNotNull(subject.shortName, subject.teacher).joinToString(" · ").ifBlank { null }

/** Whether a notice belongs on this sheet rather than on whichever raised it. */
private val ManagementNotice.isAboutSubjects: Boolean
    get() = this is ManagementNotice.SubjectAdded ||
        this is ManagementNotice.SubjectRenamed ||
        this is ManagementNotice.SubjectSaved ||
        this is ManagementNotice.SubjectDeleted

/**
 * The four fields, for both adding and editing.
 *
 * One form for the two because they are the same four boxes and the same four
 * rules; the only difference is what the fields start as and whether there is
 * anything to delete.
 */
@Composable
private fun SubjectEditor(
    subject: ManagedSubject?,
    busy: Boolean,
    failure: ManageFailure?,
    onCancel: () -> Unit,
    onSave: (SubjectForm) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val key = subject?.id ?: 0L
    var name by rememberSaveable(key) { mutableStateOf(subject?.name.orEmpty()) }
    var short by rememberSaveable(key) { mutableStateOf(subject?.shortName.orEmpty()) }
    var teacher by rememberSaveable(key) { mutableStateOf(subject?.teacher.orEmpty()) }
    var colour by rememberSaveable(key) { mutableStateOf(subject?.color.orEmpty()) }
    val problem = remember(name, short, teacher, colour) {
        subjectFormProblem(name, short, teacher, colour)
    }

    SheetSection(
        title = correctedString(
            if (subject == null) R.string.admin_subject_add_title else R.string.admin_subject_edit_title,
        ),
    )
    SheetField(
        value = name,
        onValueChange = { name = it },
        label = correctedString(R.string.admin_subject_name_label),
        enabled = !busy,
    )
    SheetField(
        value = short,
        onValueChange = { short = it },
        label = correctedString(R.string.admin_subject_short_label),
        enabled = !busy,
    )
    SheetField(
        value = teacher,
        onValueChange = { teacher = it },
        label = correctedString(R.string.admin_subject_teacher_label),
        enabled = !busy,
    )
    SheetField(
        value = colour,
        onValueChange = { colour = it },
        label = correctedString(R.string.admin_subject_colour_label),
        placeholder = correctedString(R.string.admin_subject_colour_hint),
        enabled = !busy,
    )
    if (subject != null) {
        SheetNote(text = correctedString(R.string.admin_subject_rename_note))
    }
    SheetProblem(problem = problem)
    SheetFailure(failure = failure)
    SheetButtons(
        confirmLabel = correctedString(R.string.action_save),
        onConfirm = {
            onSave(
                SubjectForm(
                    name = name.trim(),
                    shortName = short.trim().ifBlank { null },
                    teacher = teacher.trim().ifBlank { null },
                    // Normalised here so what is stored is what the swatch on
                    // this form showed; the server normalises it again.
                    color = normaliseSubjectColour(colour),
                ),
            )
        },
        onCancel = onCancel,
        enabled = problem == null,
        busy = busy,
    )
    if (onDelete != null) {
        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            GroupItem(
                title = correctedString(R.string.admin_subject_delete),
                subtitle = correctedString(R.string.admin_subject_delete_message),
                tone = errorTone(),
                onClick = onDelete,
                enabled = !busy,
            )
        }
    }
}

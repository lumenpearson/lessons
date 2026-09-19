package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.School
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/**
 * Which of the four things this sheet is doing.
 *
 * [INVITE_ONLY] is a confirm face and not a dialog, for the reason [DELETE] is
 * one: this sheet is where the decision is being made, and a scrim over the
 * card would hide the join code the admin is deciding about.
 */
private enum class ClassSheetMode { CARD, EDIT, DELETE, INVITE_ONLY }

/**
 * «⚙️ Класс»: what the class is, and the two ways to change it.
 *
 * Three faces in one sheet rather than three sheets, because they are one
 * errand — an admin opening this is looking at the card, and editing or
 * deleting is what they decided while looking at it. A second sheet on top of
 * the first would put the name they are about to type back behind a scrim.
 *
 * @param canDelete whether the delete row is drawn at all. Tidiness only: the
 *   endpoint is the owner's whatever this says, and it checks again.
 */
@Composable
fun ClassCardSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(ClassSheetMode.CARD) }
    val card = state.classCard.value

    ManagementSheet(
        title = correctedString(R.string.admin_class_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when {
            // The class is gone and so is this phone's token. Nothing below
            // this line exists any more, so nothing below it is drawn.
            state.classDeleted -> {
                // Closing this is what finally leaves: the sheet gets to say
                // what happened, and then the session and the cached timetable
                // of a class that no longer exists go with it. Both buttons do
                // it, because there is no "cancel" left to mean anything.
                val leave = {
                    viewModel.leaveDeletedClass()
                    onDismiss()
                }
                SheetSection(title = correctedString(R.string.admin_class_deleted_title))
                SheetNote(text = correctedString(R.string.admin_class_deleted_message))
                SheetButtons(
                    confirmLabel = correctedString(R.string.action_back),
                    onConfirm = leave,
                    onCancel = leave,
                    cancelLabel = correctedString(R.string.action_cancel),
                )
            }

            card == null -> {
                if (state.classCard.loading) {
                    SkeletonGroup(modifier = Modifier.padding(horizontal = ScreenPadding), rows = 4)
                } else {
                    ManagementFailureCard(
                        failure = state.classCard.failure,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        onRetry = viewModel::loadClass,
                    )
                }
            }

            mode == ClassSheetMode.EDIT -> ClassEditForm(
                card = card,
                busy = state.working,
                failure = state.writeFailure,
                search = state.schoolSearch,
                onSearchSchools = viewModel::searchSchools,
                onSchoolPage = viewModel::showSchoolPage,
                onClearSearch = viewModel::clearSchoolSearch,
                onCancel = {
                    viewModel.clearSchoolSearch()
                    mode = ClassSheetMode.CARD
                },
                onSave = { edit ->
                    viewModel.saveClass(edit)
                    viewModel.clearSchoolSearch()
                    mode = ClassSheetMode.CARD
                },
            )

            mode == ClassSheetMode.DELETE -> ClassDeleteForm(
                card = card,
                busy = state.working,
                failure = state.writeFailure,
                onCancel = { mode = ClassSheetMode.CARD },
                onDelete = viewModel::deleteClass,
            )

            mode == ClassSheetMode.INVITE_ONLY -> ClassInviteOnlyForm(
                busy = state.working,
                onCancel = { mode = ClassSheetMode.CARD },
                onConfirm = {
                    viewModel.setJoinMode(ClassJoinMode.INVITE)
                    // Back to the card, where the notice and any refusal are
                    // drawn — unlike the delete face, which stays put because
                    // what it is waiting for is the sheet being taken away.
                    mode = ClassSheetMode.CARD
                },
            )

            else -> {
                SheetNotice(
                    text = state.notice
                        ?.takeIf {
                            it == ManagementNotice.ClassSaved ||
                                it is ManagementNotice.JoinModeChanged
                        }
                        ?.asText(),
                )
                SheetFailure(failure = state.writeFailure)
                ClassFacts(
                    card = card,
                    busy = state.working,
                    // Only one direction asks first. Opening the class code
                    // back up takes nothing away from anybody, so a confirm
                    // step in front of it would be a question with one answer.
                    onJoinMode = { wanted ->
                        if (wanted == ClassJoinMode.INVITE) {
                            mode = ClassSheetMode.INVITE_ONLY
                        } else {
                            viewModel.setJoinMode(ClassJoinMode.OPEN)
                        }
                    },
                )
                GroupActionItem(
                    label = correctedString(R.string.admin_class_edit),
                    icon = Icons.Rounded.Edit,
                    onClick = { mode = ClassSheetMode.EDIT },
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
                if (canDelete) {
                    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                        GroupItem(
                            title = correctedString(R.string.admin_class_delete),
                            subtitle = correctedString(R.string.admin_class_delete_message),
                            icon = Icons.Rounded.DeleteForever,
                            tone = errorTone(),
                            onClick = { mode = ClassSheetMode.DELETE },
                        )
                    }
                } else {
                    SheetNote(text = correctedString(R.string.admin_class_owner_only))
                }
            }
        }
    }
}

/**
 * The card itself: what the class is and how much of it there is.
 *
 * @param onJoinMode the mode the tap on the join-mode row is asking for — the
 *   opposite of the one the class is in. The row says which way it goes; what
 *   happens on the way there is the caller's, because one direction needs a
 *   confirmation and the other does not.
 */
@Composable
private fun ClassFacts(
    card: ManagedClass,
    busy: Boolean,
    onJoinMode: (ClassJoinMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    RoundedCardContainer(modifier = modifier.padding(horizontal = ScreenPadding)) {
        GroupItem(
            title = card.name,
            subtitle = card.school ?: correctedString(R.string.admin_class_school_empty),
            icon = Icons.Rounded.School,
            tone = accentTone(1),
        )
        GroupItem(
            title = card.city ?: correctedString(R.string.admin_class_city_empty),
            icon = Icons.Rounded.LocationCity,
            tone = accentTone(2),
        )
        GroupItem(
            title = card.timezoneLabel,
            subtitle = card.timezone,
            icon = Icons.Rounded.Public,
            tone = accentTone(3),
        )
        // The code an admin reads out to the class. It is on an admin-only
        // screen for exactly that reason, and it is drawn at full weight
        // because reading it off a subtitle is how it gets mistyped.
        GroupItem(
            title = correctedString(R.string.admin_class_join_code),
            icon = Icons.Rounded.Badge,
            tone = accentTone(4),
            trailing = {
                Text(
                    text = card.joinCode,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        // Directly under the code, because it is the sentence that says whether
        // the code above it does anything at all. The subtitle is about a
        // phone rather than about the setting: «invite» means nothing to
        // somebody who has not read the bot's help, and «код класса никого не
        // подключает» means exactly one thing.
        val inviteOnly = card.joinMode == ClassJoinMode.INVITE
        GroupItem(
            title = correctedString(
                if (inviteOnly) {
                    R.string.admin_class_join_mode_invite
                } else {
                    R.string.admin_class_join_mode_open
                },
            ),
            subtitle = correctedString(
                if (inviteOnly) {
                    R.string.admin_class_join_mode_invite_note
                } else {
                    R.string.admin_class_join_mode_open_note
                },
            ),
            icon = if (inviteOnly) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            tone = accentTone(if (inviteOnly) 5 else 4),
            enabled = !busy,
            onClick = {
                onJoinMode(if (inviteOnly) ClassJoinMode.OPEN else ClassJoinMode.INVITE)
            },
        )
        CountRow(
            title = correctedString(R.string.admin_class_members),
            count = card.members,
            icon = Icons.Rounded.Groups,
            tone = 0,
        )
        CountRow(
            title = correctedString(R.string.admin_class_devices),
            count = card.devices,
            icon = Icons.Rounded.PhoneAndroid,
            tone = 5,
        )
        CountRow(
            title = correctedString(R.string.admin_class_requests),
            count = card.pendingRequests,
            icon = Icons.Rounded.Groups,
            tone = 2,
        )
        GroupItem(
            title = correctedString(R.string.admin_class_calendar),
            subtitle = correctedString(
                if (card.calendarReady) {
                    R.string.admin_class_calendar_ready
                } else {
                    R.string.admin_class_calendar_missing
                },
            ),
            icon = Icons.Rounded.CalendarMonth,
            tone = accentTone(3),
        )
    }
}

@Composable
private fun CountRow(title: String, count: Int, icon: ImageVector, tone: Int) {
    GroupItem(
        title = title,
        icon = icon,
        tone = accentTone(tone),
        trailing = {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * The four boxes, and the eleven zones.
 *
 * Blank school and city mean «убрать», which is the only thing a form with every
 * field on screen can mean by an empty box — and the server reads the explicit
 * null it turns into the same way.
 */
@Composable
private fun ClassEditForm(
    card: ManagedClass,
    busy: Boolean,
    failure: ManageFailure?,
    search: SchoolSearch,
    onSearchSchools: (String) -> Unit,
    onSchoolPage: (Int) -> Unit,
    onClearSearch: () -> Unit,
    onCancel: () -> Unit,
    onSave: (ClassEdit) -> Unit,
) {
    var name by rememberSaveable(card.id) { mutableStateOf(card.name) }
    var school by rememberSaveable(card.id) { mutableStateOf(card.school.orEmpty()) }
    var city by rememberSaveable(card.id) { mutableStateOf(card.city.orEmpty()) }
    var zone by rememberSaveable(card.id) { mutableStateOf(card.timezone) }
    val problem = remember(name, school, city) { classFormProblem(name, school, city) }

    SheetSection(title = correctedString(R.string.admin_class_edit_title))
    SheetField(
        value = name,
        onValueChange = { name = it },
        label = correctedString(R.string.admin_class_name_label),
        enabled = !busy,
    )
    SheetField(
        value = school,
        onValueChange = { school = it },
        label = correctedString(R.string.admin_class_school_label),
        enabled = !busy,
    )
    // The directory fills the box above; it never writes the class on its own.
    // Picking a school is still an edit somebody has to save, and the name it
    // put there stays editable — a register spelling is not always the one a
    // class calls itself.
    SchoolSearchPanel(
        search = search,
        enabled = !busy,
        onSearch = onSearchSchools,
        onPage = onSchoolPage,
        onPick = { picked ->
            school = picked.name
            if (city.isBlank()) picked.city?.let { city = it }
            onClearSearch()
        },
    )
    SheetField(
        value = city,
        onValueChange = { city = it },
        label = correctedString(R.string.admin_class_city_label),
        enabled = !busy,
    )
    SheetSection(title = correctedString(R.string.admin_class_timezone_label))
    SheetNote(text = correctedString(R.string.admin_class_timezone_note))
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        zoneOptions(card.timezone).forEach { option ->
            GroupItem(
                title = option.offset,
                subtitle = option.cities.ifBlank { option.id },
                tone = accentTone(3),
                onClick = { zone = option.id },
                trailing = {
                    if (option.id == zone) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }
    }
    SheetProblem(problem = problem)
    SheetFailure(failure = failure)
    SheetButtons(
        confirmLabel = correctedString(R.string.action_save),
        onConfirm = {
            onSave(
                ClassEdit(
                    name = name.trim(),
                    school = school.trim().ifBlank { null },
                    city = city.trim().ifBlank { null },
                    timezone = zone,
                ),
            )
        },
        onCancel = onCancel,
        enabled = problem == null,
        busy = busy,
    )
}


/**
 * «Найти в реестре»: a query, five results at a time, and a pager.
 *
 * The whole answer is searched once and paged in the view model, because the
 * directory has no offset — the server searches again on every request, so a
 * next-page button that called it would be a second search for the same
 * question. See [SchoolSearch].
 *
 * Nothing here can fail in a way that blocks the form. A directory that is not
 * configured or not answering draws its own sentence and the box above still
 * takes a typed name, which is what this screen did before the directory
 * existed.
 */
@Composable
private fun SchoolSearchPanel(
    search: SchoolSearch,
    enabled: Boolean,
    onSearch: (String) -> Unit,
    onPage: (Int) -> Unit,
    onPick: (School) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    SheetNote(text = correctedString(R.string.admin_class_school_search_note))
    SheetField(
        value = query,
        onValueChange = { query = it },
        label = correctedString(R.string.admin_class_school_search_label),
        placeholder = correctedString(R.string.admin_class_school_search_hint),
        enabled = enabled && !search.searching,
    )
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        GroupItem(
            title = correctedString(
                if (search.searching) {
                    R.string.admin_class_school_searching
                } else {
                    R.string.admin_class_school_search_action
                },
            ),
            icon = Icons.Rounded.Search,
            tone = accentTone(2),
            enabled = enabled && !search.searching && query.isNotBlank(),
            onClick = { onSearch(query) },
        )
    }

    search.unavailable?.let { SheetNote(text = it) }
    SheetFailure(failure = search.failure)

    if (search.searched && search.results.isEmpty() && search.unavailable == null) {
        SheetNote(text = correctedString(R.string.admin_class_school_search_empty))
    }

    if (search.results.isNotEmpty()) {
        SheetNote(
            text = if (search.truncated) {
                // Twenty is the directory's ceiling, not the number of matches.
                // Saying «найдено 20» would hide the other two hundred and
                // eighty and give no reason to type more.
                correctedString(R.string.admin_class_school_search_truncated)
            } else {
                correctedString(R.string.admin_class_school_search_found, search.total)
            },
        )
        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            search.visible.forEach { found ->
                GroupItem(
                    title = found.name,
                    subtitle = listOfNotNull(
                        found.city,
                        correctedString(R.string.admin_class_school_closed).takeIf { !found.active },
                    ).joinToString(" · ").ifBlank { null },
                    icon = Icons.Rounded.School,
                    tone = accentTone(1),
                    enabled = enabled,
                    onClick = { onPick(found) },
                )
            }
        }
        if (search.pages > 1) {
            RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                GroupItem(
                    title = correctedString(
                        R.string.admin_class_school_page,
                        search.page,
                        search.pages,
                    ),
                    tone = accentTone(4),
                    trailing = {
                        Row {
                            IconButton(
                                onClick = { onPage(search.page - 1) },
                                enabled = enabled && search.page > 1,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronLeft,
                                    contentDescription = correctedString(R.string.action_back),
                                )
                            }
                            IconButton(
                                onClick = { onPage(search.page + 1) },
                                enabled = enabled && search.page < search.pages,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = correctedString(
                                        R.string.admin_class_school_next,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The confirmation the endpoint insists on: the class's own name, typed back.
 *
 * The button being dark until the name matches is a courtesy. The check that
 * matters is the server's, because `DELETE /manage/class` is reachable without
 * ever opening this sheet — which is precisely why a «вы уверены?» button would
 * not have been enough: the same thumb presses it that pressed the one before.
 */
@Composable
private fun ClassDeleteForm(
    card: ManagedClass,
    busy: Boolean,
    failure: ManageFailure?,
    onCancel: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var typed by rememberSaveable(card.id) { mutableStateOf("") }
    val problem = remember(typed, card.name) {
        if (typed.isEmpty()) null else deleteConfirmProblem(typed, card.name)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SheetSection(title = correctedString(R.string.admin_class_delete_title))
        SheetNote(text = correctedString(R.string.admin_class_delete_message))
    }
    SheetField(
        value = typed,
        onValueChange = { typed = it },
        label = correctedString(R.string.admin_class_delete_confirm, card.name),
        enabled = !busy,
        modifier = Modifier.padding(top = 8.dp),
    )
    SheetProblem(problem = problem)
    SheetFailure(failure = failure)
    SheetButtons(
        confirmLabel = correctedString(R.string.admin_class_delete),
        onConfirm = { onDelete(typed) },
        onCancel = onCancel,
        enabled = confirmsClassName(typed, card.name),
        busy = busy,
        destructive = true,
    )
}

/**
 * The one question in front of switching the class code off.
 *
 * It exists because the tap that reaches it is the same size as the tap that
 * opens the timezone list, and the thing it does is invisible from this phone:
 * nothing on this screen changes, and the person who finds out is a pupil
 * typing a code that worked yesterday. So the sentence has to carry all three
 * true things — that nobody is disconnected, that the code is what stops, and
 * where the personal code comes from instead — rather than «вы уверены?».
 */
@Composable
private fun ClassInviteOnlyForm(
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetSection(title = correctedString(R.string.admin_class_join_mode_confirm_title))
        SheetNote(text = correctedString(R.string.admin_class_join_mode_confirm_message))
    }
    // No `SheetFailure` here, unlike the delete face: confirming returns to the
    // card before the write answers, so a refusal is drawn there. One that
    // could never render would be a promise this face does not keep.
    SheetButtons(
        confirmLabel = correctedString(R.string.admin_class_join_mode_confirm_action),
        onConfirm = onConfirm,
        onCancel = onCancel,
        busy = busy,
    )
}

/** Only an owner is offered the delete row; the server refuses everyone else. */
fun canDeleteClass(role: ClassRole?): Boolean = role == ClassRole.OWNER

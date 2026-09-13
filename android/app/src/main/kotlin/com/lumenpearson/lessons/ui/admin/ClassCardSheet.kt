package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/** Which of the three things this sheet is doing. */
private enum class ClassSheetMode { CARD, EDIT, DELETE }

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
        title = stringResource(R.string.admin_class_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when {
            // The class is gone and so is this phone's token. Nothing below
            // this line exists any more, so nothing below it is drawn.
            state.classDeleted -> {
                SheetSection(title = stringResource(R.string.admin_class_deleted_title))
                SheetNote(text = stringResource(R.string.admin_class_deleted_message))
                SheetButtons(
                    confirmLabel = stringResource(R.string.action_back),
                    onConfirm = onDismiss,
                    onCancel = onDismiss,
                    cancelLabel = stringResource(R.string.action_cancel),
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
                onCancel = { mode = ClassSheetMode.CARD },
                onSave = { edit ->
                    viewModel.saveClass(edit)
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

            else -> {
                SheetNotice(text = state.notice?.takeIf { it == ManagementNotice.ClassSaved }?.asText())
                SheetFailure(failure = state.writeFailure)
                ClassFacts(card)
                GroupActionItem(
                    label = stringResource(R.string.admin_class_edit),
                    icon = Icons.Rounded.Edit,
                    onClick = { mode = ClassSheetMode.EDIT },
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
                if (canDelete) {
                    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                        GroupItem(
                            title = stringResource(R.string.admin_class_delete),
                            subtitle = stringResource(R.string.admin_class_delete_message),
                            icon = Icons.Rounded.DeleteForever,
                            tone = errorTone(),
                            onClick = { mode = ClassSheetMode.DELETE },
                        )
                    }
                } else {
                    SheetNote(text = stringResource(R.string.admin_class_owner_only))
                }
            }
        }
    }
}

/** The card itself: what the class is and how much of it there is. */
@Composable
private fun ClassFacts(card: ManagedClass, modifier: Modifier = Modifier) {
    RoundedCardContainer(modifier = modifier.padding(horizontal = ScreenPadding)) {
        GroupItem(
            title = card.name,
            subtitle = card.school ?: stringResource(R.string.admin_class_school_empty),
            icon = Icons.Rounded.School,
            tone = accentTone(1),
        )
        GroupItem(
            title = card.city ?: stringResource(R.string.admin_class_city_empty),
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
            title = stringResource(R.string.admin_class_join_code),
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
        CountRow(
            title = stringResource(R.string.admin_class_members),
            count = card.members,
            icon = Icons.Rounded.Groups,
            tone = 0,
        )
        CountRow(
            title = stringResource(R.string.admin_class_devices),
            count = card.devices,
            icon = Icons.Rounded.PhoneAndroid,
            tone = 5,
        )
        CountRow(
            title = stringResource(R.string.admin_class_requests),
            count = card.pendingRequests,
            icon = Icons.Rounded.Groups,
            tone = 2,
        )
        GroupItem(
            title = stringResource(R.string.admin_class_calendar),
            subtitle = stringResource(
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
    onCancel: () -> Unit,
    onSave: (ClassEdit) -> Unit,
) {
    var name by rememberSaveable(card.id) { mutableStateOf(card.name) }
    var school by rememberSaveable(card.id) { mutableStateOf(card.school.orEmpty()) }
    var city by rememberSaveable(card.id) { mutableStateOf(card.city.orEmpty()) }
    var zone by rememberSaveable(card.id) { mutableStateOf(card.timezone) }
    val problem = remember(name, school, city) { classFormProblem(name, school, city) }

    SheetSection(title = stringResource(R.string.admin_class_edit_title))
    SheetField(
        value = name,
        onValueChange = { name = it },
        label = stringResource(R.string.admin_class_name_label),
        enabled = !busy,
    )
    SheetField(
        value = school,
        onValueChange = { school = it },
        label = stringResource(R.string.admin_class_school_label),
        enabled = !busy,
    )
    SheetField(
        value = city,
        onValueChange = { city = it },
        label = stringResource(R.string.admin_class_city_label),
        enabled = !busy,
    )
    SheetSection(title = stringResource(R.string.admin_class_timezone_label))
    SheetNote(text = stringResource(R.string.admin_class_timezone_note))
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
        confirmLabel = stringResource(R.string.action_save),
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
        SheetSection(title = stringResource(R.string.admin_class_delete_title))
        SheetNote(text = stringResource(R.string.admin_class_delete_message))
    }
    SheetField(
        value = typed,
        onValueChange = { typed = it },
        label = stringResource(R.string.admin_class_delete_confirm, card.name),
        enabled = !busy,
        modifier = Modifier.padding(top = 8.dp),
    )
    SheetProblem(problem = problem)
    SheetFailure(failure = failure)
    SheetButtons(
        confirmLabel = stringResource(R.string.admin_class_delete),
        onConfirm = { onDelete(typed) },
        onCancel = onCancel,
        enabled = confirmsClassName(typed, card.name),
        busy = busy,
        destructive = true,
    )
}

/** Only an owner is offered the delete row; the server refuses everyone else. */
fun canDeleteClass(role: ClassRole?): Boolean = role == ClassRole.OWNER

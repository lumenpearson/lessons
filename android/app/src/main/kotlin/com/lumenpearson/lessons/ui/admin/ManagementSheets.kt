package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The chrome the eight management sheets share, and the words for everything
 * the server can answer.
 *
 * Every one of these screens is a sheet rather than a page because the page it
 * would have to be a page *of* is the settings tree, and a settings section is
 * a list of rows that somebody else owns. A sheet costs nothing to reach from a
 * row, comes in from the edge the keyboard comes from — which matters, because
 * six of the eight have a text field in them — and leaves the list underneath
 * visible, which is where the user was.
 *
 * The one rule running through the file: a refusal is drawn where it happened
 * and says what the server said. Nothing here turns an answer into silence.
 */

/** The sheet shape: a title, a scrolling body, and no scaffold of its own. */
@Composable
fun ManagementSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = title,
        content = content,
    )
}

/** A paragraph of explanation inside a sheet, in the muted body colour. */
@Composable
fun SheetNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = ScreenPadding),
    )
}

/**
 * A heading inside a sheet, so a long sheet reads as sections and not as a list.
 *
 * The same [ScreenPadding] every other element of a sheet gets. A settings page
 * gets it once, from its list's content padding; a sheet has none of its own —
 * see [LessonsBottomSheet] — so each element adds it, and this one has to as
 * well or its label starts 16 dp left of the rows it introduces.
 */
@Composable
fun SheetSection(title: String, modifier: Modifier = Modifier) {
    SectionHeader(title = title, modifier = modifier.padding(horizontal = ScreenPadding))
}

/**
 * A text field of a management form.
 *
 * Copied in shape from `ServerUrlSheet`, down to the emphasised label while the
 * field has focus: this is the app's one text-entry accent and six sheets here
 * would otherwise each invent their own.
 */
@Composable
fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        interactionSource = interactionSource,
        label = {
            Text(text = label, style = LocalTextStyle.current.emphasised(focused))
        },
        placeholder = placeholder?.let { { Text(text = it) } },
    )
}

/**
 * The row of buttons at the foot of a sheet.
 *
 * @param destructive draws the confirming button in the error colour. Used by
 *   the three sheets that delete something, so that "удалить" never looks like
 *   "сохранить" to a thumb moving quickly.
 */
@Composable
fun SheetButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false,
    cancelLabel: String = stringResource(R.string.action_cancel),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, enabled = !busy) {
            Text(text = cancelLabel)
        }
        Button(
            onClick = onConfirm,
            enabled = enabled && !busy,
            colors = if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(text = confirmLabel)
            }
        }
    }
}

/**
 * A refusal, said in words, with the server's own sentence under it.
 *
 * The second line is deliberately the raw `detail`, in whatever language the
 * server writes. It is the only thing that separates «это основное расписание»
 * from «на него ссылаются три особых дня», and an app that dropped it because
 * it was not translated would be hiding the one fact the admin needs to act.
 */
@Composable
fun ManagementFailureCard(
    failure: ManageFailure?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    if (failure == null) return
    val detail = failure.detailText()
    EmptyState(
        title = stringResource(R.string.admin_error_title),
        description = listOfNotNull(failure.asText(), detail).joinToString("\n"),
        modifier = modifier,
        // Only the answers a second press could change get a button. Pressing
        // "повторить" on a 409 asks the same question and is told the same no.
        actionLabel = stringResource(R.string.admin_retry).takeIf { onRetry != null && failure.isRetryable },
        onActionClick = onRetry.takeIf { failure.isRetryable },
    )
}

/** A refusal inline under the control that caused it, for a sheet mid-edit. */
@Composable
fun SheetFailure(failure: ManageFailure?, modifier: Modifier = Modifier) {
    if (failure == null) return
    val detail = failure.detailText()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = failure.asText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A form's own objection, before anything is sent. */
@Composable
fun SheetProblem(problem: FormProblem?, modifier: Modifier = Modifier) {
    if (problem == null) return
    Text(
        text = problem.asText(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(horizontal = ScreenPadding),
    )
}

/** One line saying what just happened, above the thing it happened to. */
@Composable
fun SheetNotice(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = ScreenPadding),
    )
}

/** Whether pressing the same button again could plausibly answer differently. */
private val ManageFailure.isRetryable: Boolean
    get() = this is ManageFailure.Offline || this is ManageFailure.Unexpected

/** Every refusal this surface can produce, as one sentence each. */
@Composable
fun ManageFailure.asText(): String = when (this) {
    ManageFailure.SignedOut -> stringResource(R.string.admin_error_signed_out)
    ManageFailure.NotLinked -> stringResource(R.string.admin_error_not_linked)
    is ManageFailure.RoleLost -> stringResource(R.string.admin_error_role_lost)
    is ManageFailure.NotAllowed -> stringResource(R.string.admin_error_not_allowed)
    ManageFailure.NotFound -> stringResource(R.string.admin_error_not_found)
    is ManageFailure.Refused -> stringResource(R.string.admin_error_refused)
    is ManageFailure.Invalid -> stringResource(R.string.admin_error_invalid)
    // The server's own sentence, not ours: a 503 here is a feature that is off
    // on this deployment, and only the server knows which one and what to do
    // instead. Falls back to a generic line for a body without a detail.
    is ManageFailure.Unavailable ->
        detail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.admin_error_unavailable)

    is ManageFailure.Offline -> stringResource(R.string.admin_error_offline)
    is ManageFailure.Unexpected -> stringResource(
        R.string.admin_error_unknown,
        code?.toString() ?: reason?.javaClass?.simpleName.orEmpty(),
    )
}

/** The server's own `detail`, where it has one worth reading. */
@Composable
fun ManageFailure.detailText(): String? {
    val detail = when (this) {
        is ManageFailure.NotAllowed -> detail
        is ManageFailure.Refused -> detail
        is ManageFailure.Invalid -> detail
        // Not repeated here: [asText] already *is* the server's sentence for
        // this one, and the detail line underneath would say it twice.
        else -> null
    }
    return detail?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.admin_error_detail, it) }
}

/** What a form is objecting to. */
@Composable
fun FormProblem.asText(): String = when (this) {
    FormProblem.NAME_BLANK -> stringResource(R.string.admin_form_name_blank)
    FormProblem.NAME_TOO_LONG -> stringResource(R.string.admin_form_name_long)
    FormProblem.SHORT_NAME_TOO_LONG -> stringResource(R.string.admin_form_short_name_long)
    FormProblem.TEACHER_TOO_LONG -> stringResource(R.string.admin_form_teacher_long)
    FormProblem.SCHOOL_TOO_LONG -> stringResource(R.string.admin_form_school_long)
    FormProblem.CITY_TOO_LONG -> stringResource(R.string.admin_form_city_long)
    FormProblem.COLOUR_UNREADABLE -> stringResource(R.string.admin_form_colour)
    FormProblem.NO_PERIODS -> stringResource(R.string.admin_form_no_periods)
    FormProblem.TOO_MANY_PERIODS -> stringResource(R.string.admin_form_too_many_periods)
    FormProblem.PERIOD_NUMBER_REPEATED -> stringResource(R.string.admin_form_period_repeated)
    FormProblem.PERIOD_NUMBER_OUT_OF_RANGE -> stringResource(R.string.admin_form_period_range)
    FormProblem.PERIOD_ENDS_BEFORE_IT_STARTS -> stringResource(R.string.admin_form_period_backwards)
    FormProblem.EMPTY_PASTE -> stringResource(R.string.admin_form_paste_empty)
    FormProblem.PASTE_TOO_LONG -> stringResource(R.string.admin_form_paste_long)
    FormProblem.NAME_DOES_NOT_MATCH -> stringResource(R.string.admin_form_name_mismatch)
}

/** What just happened, in one line. */
@Composable
fun ManagementNotice.asText(): String = when (this) {
    is ManagementNotice.SubjectAdded -> stringResource(R.string.admin_subject_added, name)
    is ManagementNotice.SubjectRenamed ->
        stringResource(R.string.admin_subject_renamed, name, moved)

    is ManagementNotice.SubjectSaved -> stringResource(R.string.admin_subject_saved, name)
    is ManagementNotice.SubjectDeleted -> stringResource(R.string.admin_subject_deleted, name)
    ManagementNotice.ClassSaved -> stringResource(R.string.admin_class_saved)
    is ManagementNotice.JoinModeChanged -> stringResource(
        if (mode == ClassJoinMode.INVITE) {
            R.string.admin_class_join_mode_set_invite
        } else {
            R.string.admin_class_join_mode_set_open
        },
    )
    is ManagementNotice.BellsSaved -> stringResource(R.string.admin_bells_saved, name)
    is ManagementNotice.BellsDefault -> stringResource(R.string.admin_bells_default_set, name)
    is ManagementNotice.BellsDeleted -> stringResource(R.string.admin_bells_deleted, name)
    is ManagementNotice.Imported ->
        stringResource(R.string.admin_timetable_imported, days, lessons, bells)

    ManagementNotice.DeviceRevoked -> stringResource(R.string.admin_device_revoked_notice)
    ManagementNotice.DeviceUnlinked -> stringResource(R.string.admin_device_unlinked_notice)
    is ManagementNotice.RequestApproved -> if (role == null) {
        stringResource(R.string.admin_request_approved_plain, who)
    } else {
        stringResource(R.string.admin_request_approved, who, role.roleName())
    }

    is ManagementNotice.RequestDeclined -> stringResource(R.string.admin_request_declined, who)
}

/** A role, as the bot names it. */
@Composable
fun ClassRole?.roleName(): String = when (this) {
    ClassRole.VIEWER -> stringResource(R.string.admin_role_name_viewer)
    ClassRole.EDITOR -> stringResource(R.string.admin_role_name_editor)
    ClassRole.ADMIN -> stringResource(R.string.admin_role_name_admin)
    ClassRole.OWNER -> stringResource(R.string.admin_role_name_owner)
    null -> stringResource(R.string.admin_role_name_unknown)
}

/**
 * "12.09 14:05" — a stamp from this surface, on the class's own clock.
 *
 * Deliberately not `syncedAtLabel`: that one is about the device's clock and
 * says «сегодня», which would be a lie about a class in another zone whose
 * today is not this phone's.
 */
fun LocalDateTime.asClassStamp(): String = format(ClassStampFormatter)

private val ClassStampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** "понедельник", declined by java.time rather than by a string array. */
fun DayOfWeek.asWeekdayName(locale: Locale = Locale.getDefault()): String =
    getDisplayName(TextStyle.FULL, locale)

package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/** What the requests sheet is doing: the list, or one decision. */
private sealed interface RequestsMode {
    data object List : RequestsMode
    data class Approve(val request: AccessRequest) : RequestsMode
    data class Decline(val request: AccessRequest) : RequestsMode
}

/**
 * «🙋 Запросы доступа»: everybody waiting for a role.
 *
 * Approving asks which role, because the endpoint takes one and the bot's
 * «Выдать» is only the default answer — an admin who reads «я староста» may
 * decide that «редактор» is the right role whatever was asked for.
 *
 * The list of roles offered stops below the caller's own, which is the rule the
 * server applies twice: nobody may grant at or above their level, and nobody
 * may change a peer's role. Both refusals come back as an ordinary `403` and
 * are drawn here as a refusal of that one grant — they do not take the page
 * away, because the administrator is still an administrator.
 */
@Composable
fun RequestsSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    role: ClassRole?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf<RequestsMode>(RequestsMode.List) }
    val requests = state.requests.value
    val grantable = remember(role) { grantableRoles(role) }

    ManagementSheet(
        title = stringResource(R.string.admin_requests_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when (val current = mode) {
            is RequestsMode.Approve -> {
                SheetSection(title = current.request.who)
                SheetNote(text = stringResource(R.string.admin_request_role_title))
                if (grantable.isEmpty()) {
                    SheetNote(text = stringResource(R.string.admin_request_none_grantable))
                }
                RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    // "As asked" first, because it is what pressing «Выдать» in
                    // the bot does and what an admin skimming expects to press.
                    GroupItem(
                        title = stringResource(
                            R.string.admin_request_role_asked,
                            current.request.requestedRole.roleName(),
                        ),
                        icon = Icons.Rounded.Check,
                        tone = accentTone(1),
                        enabled = !state.working,
                        onClick = {
                            viewModel.approveRequest(current.request, null)
                            mode = RequestsMode.List
                        },
                    )
                    grantable.forEach { option ->
                        GroupItem(
                            title = option.roleName(),
                            tone = accentTone(option.ordinal + 2),
                            enabled = !state.working,
                            onClick = {
                                viewModel.approveRequest(current.request, option)
                                mode = RequestsMode.List
                            },
                        )
                    }
                }
                SheetFailure(failure = state.writeFailure)
                SheetButtons(
                    confirmLabel = stringResource(R.string.action_back),
                    onConfirm = { mode = RequestsMode.List },
                    onCancel = { mode = RequestsMode.List },
                    busy = state.working,
                )
            }

            is RequestsMode.Decline -> {
                SheetSection(title = current.request.who)
                SheetNote(text = stringResource(R.string.admin_request_decline_message))
                SheetFailure(failure = state.writeFailure)
                SheetButtons(
                    confirmLabel = stringResource(R.string.admin_request_decline),
                    onConfirm = {
                        viewModel.declineRequest(current.request)
                        mode = RequestsMode.List
                    },
                    onCancel = { mode = RequestsMode.List },
                    busy = state.working,
                    destructive = true,
                )
            }

            RequestsMode.List -> {
                SheetNotice(text = state.notice?.takeIf { it.isAboutRequests }?.asText())
                SheetFailure(failure = state.writeFailure)
                when {
                    requests == null && state.requests.loading -> SkeletonGroup(
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        rows = 3,
                    )

                    requests == null -> ManagementFailureCard(
                        failure = state.requests.failure,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        onRetry = viewModel::loadRequests,
                    )

                    requests.isEmpty() -> EmptyState(
                        title = stringResource(R.string.admin_requests_empty_title),
                        description = stringResource(R.string.admin_requests_empty_text),
                        icon = Icons.Rounded.PersonAdd,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )

                    else -> requests.forEach { request ->
                        SheetSection(title = request.who)
                        RoundedCardContainer(
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        ) {
                            GroupItem(
                                title = stringResource(
                                    R.string.admin_request_asked,
                                    request.requestedRole.roleName(),
                                ),
                                // The message is why they think they should
                                // have it, and is the whole of what an admin
                                // has to go on.
                                subtitle = listOfNotNull(
                                    request.message,
                                    request.createdAt?.asClassStamp(),
                                ).joinToString(" · ").ifBlank { null },
                                icon = Icons.Rounded.PersonAdd,
                                tone = accentTone(2),
                            )
                            GroupItem(
                                title = stringResource(R.string.admin_request_approve),
                                icon = Icons.Rounded.Check,
                                tone = accentTone(0),
                                enabled = !state.working,
                                onClick = { mode = RequestsMode.Approve(request) },
                            )
                            GroupItem(
                                title = stringResource(R.string.admin_request_decline),
                                tone = errorTone(),
                                enabled = !state.working,
                                onClick = { mode = RequestsMode.Decline(request) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val ManagementNotice.isAboutRequests: Boolean
    get() = this is ManagementNotice.RequestApproved || this is ManagementNotice.RequestDeclined

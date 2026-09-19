package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/** What the requests sheet is doing: the list, or one decision. */
internal sealed interface RequestsMode {
    data object List : RequestsMode
    data class Approve(val request: AccessRequest) : RequestsMode
    data class Decline(val request: AccessRequest) : RequestsMode
}

/**
 * Which of the sheet's screens is up — the half of [RequestsMode] that can be
 * saved; [bellsModeOf] says why the other half is resolved rather than saved.
 */
internal enum class RequestsScreen { LIST, APPROVE, DECLINE }

/**
 * The mode [screen] and [id] stand for, against the requests actually on hand.
 *
 * A screen that needs a request and has none falls back to the list, and this is
 * the sheet where that happens for real rather than as a precaution: a request
 * is answered in the bot as readily as here, and an approval card for one that
 * has already been granted offers a row of roles that can only come back `404`.
 */
internal fun requestsModeOf(
    screen: RequestsScreen,
    id: Long?,
    requests: List<AccessRequest>?,
): RequestsMode {
    val request = requests?.firstOrNull { it.id == id } ?: return RequestsMode.List
    return when (screen) {
        RequestsScreen.APPROVE -> RequestsMode.Approve(request)
        RequestsScreen.DECLINE -> RequestsMode.Decline(request)
        RequestsScreen.LIST -> RequestsMode.List
    }
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
    // The screen and the id, not the mode: a rotation — and, below API 33, the
    // `recreate()` the language picker three pages away performs — rebuilds this
    // composable from nothing, and a `remember` here put the reader back on the
    // list halfway through deciding. See [requestsModeOf].
    var screen by rememberSaveable { mutableStateOf(RequestsScreen.LIST) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val requests = state.requests.value
    val mode = remember(screen, openId, requests) { requestsModeOf(screen, openId, requests) }
    val grantable = remember(role) { grantableRoles(role) }

    fun show(next: RequestsScreen, request: AccessRequest? = null) {
        screen = next
        openId = request?.id
    }

    ManagementSheet(
        title = correctedString(R.string.admin_requests_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when (val current = mode) {
            is RequestsMode.Approve -> {
                SheetSection(title = current.request.who)
                SheetNote(text = correctedString(R.string.admin_request_role_title))
                if (grantable.isEmpty()) {
                    SheetNote(text = correctedString(R.string.admin_request_none_grantable))
                }
                RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    // "As asked" first, because it is what pressing «Выдать» in
                    // the bot does and what an admin skimming expects to press.
                    GroupItem(
                        title = correctedString(
                            R.string.admin_request_role_asked,
                            current.request.requestedRole.roleName(),
                        ),
                        icon = Icons.Rounded.Check,
                        tone = accentTone(1),
                        enabled = !state.working,
                        onClick = {
                            viewModel.approveRequest(current.request, null)
                            show(RequestsScreen.LIST)
                        },
                    )
                    grantable.forEach { option ->
                        GroupItem(
                            title = option.roleName(),
                            tone = accentTone(option.ordinal + 2),
                            enabled = !state.working,
                            onClick = {
                                viewModel.approveRequest(current.request, option)
                                show(RequestsScreen.LIST)
                            },
                        )
                    }
                }
                SheetFailure(failure = state.writeFailure)
                SheetButtons(
                    confirmLabel = correctedString(R.string.action_back),
                    onConfirm = { show(RequestsScreen.LIST) },
                    onCancel = { show(RequestsScreen.LIST) },
                    busy = state.working,
                )
            }

            is RequestsMode.Decline -> {
                SheetSection(title = current.request.who)
                SheetNote(text = correctedString(R.string.admin_request_decline_message))
                SheetFailure(failure = state.writeFailure)
                SheetButtons(
                    confirmLabel = correctedString(R.string.admin_request_decline),
                    onConfirm = {
                        viewModel.declineRequest(current.request)
                        show(RequestsScreen.LIST)
                    },
                    onCancel = { show(RequestsScreen.LIST) },
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
                        title = correctedString(R.string.admin_requests_empty_title),
                        description = correctedString(R.string.admin_requests_empty_text),
                        icon = Icons.Rounded.PersonAdd,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )

                    else -> requests.forEach { request ->
                        SheetSection(title = request.who)
                        RoundedCardContainer(
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        ) {
                            GroupItem(
                                title = correctedString(
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
                                title = correctedString(R.string.admin_request_approve),
                                icon = Icons.Rounded.Check,
                                tone = accentTone(0),
                                enabled = !state.working,
                                onClick = { show(RequestsScreen.APPROVE, request) },
                            )
                            GroupItem(
                                title = correctedString(R.string.admin_request_decline),
                                tone = errorTone(),
                                enabled = !state.working,
                                onClick = { show(RequestsScreen.DECLINE, request) },
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

package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone

/** What the devices sheet is doing: the list, or one confirmation. */
private sealed interface DevicesMode {
    data object List : DevicesMode
    data class Revoke(val device: ManagedDevice) : DevicesMode
    data class Unlink(val device: ManagedDevice) : DevicesMode
}

/**
 * «📱 Устройства»: every phone on the class's list.
 *
 * Both actions are behind a confirmation, and neither confirmation is
 * decoration. Revoking may be the phone in the caller's own hand, which is a
 * legitimate thing to do — it is how a lost phone is dealt with from the one
 * still in a pocket — and the app on it is signed out at once; unlinking takes
 * a role away from somebody who will not be told by this screen.
 */
@Composable
fun DevicesSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf<DevicesMode>(DevicesMode.List) }
    val devices = state.devices.value

    ManagementSheet(
        title = correctedString(R.string.admin_devices_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when (val current = mode) {
            is DevicesMode.Revoke -> Confirmation(
                title = correctedString(R.string.admin_device_revoke_title),
                message = correctedString(R.string.admin_device_revoke_message),
                confirmLabel = correctedString(R.string.admin_device_revoke),
                busy = state.working,
                failure = state.writeFailure,
                onConfirm = {
                    viewModel.revokeDevice(current.device)
                    mode = DevicesMode.List
                },
                onCancel = { mode = DevicesMode.List },
            )

            is DevicesMode.Unlink -> Confirmation(
                title = correctedString(R.string.admin_device_unlink_title),
                message = correctedString(R.string.admin_device_unlink_message),
                confirmLabel = correctedString(R.string.admin_device_unlink),
                busy = state.working,
                failure = state.writeFailure,
                onConfirm = {
                    viewModel.unlinkDevice(current.device)
                    mode = DevicesMode.List
                },
                onCancel = { mode = DevicesMode.List },
            )

            DevicesMode.List -> {
                SheetNotice(text = state.notice?.takeIf { it.isAboutDevices }?.asText())
                SheetFailure(failure = state.writeFailure)
                RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    GroupSwitchItem(
                        title = correctedString(R.string.admin_devices_show_revoked),
                        icon = Icons.Rounded.PhonelinkErase,
                        tone = accentTone(5),
                        checked = state.showRevokedDevices,
                        onCheckedChange = viewModel::loadDevices,
                    )
                }
                when {
                    devices == null && state.devices.loading -> SkeletonGroup(
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        rows = 4,
                    )

                    devices == null -> ManagementFailureCard(
                        failure = state.devices.failure,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        onRetry = { viewModel.loadDevices() },
                    )

                    devices.isEmpty() -> EmptyState(
                        title = correctedString(R.string.admin_devices_empty_title),
                        description = correctedString(R.string.admin_devices_empty_text),
                        icon = Icons.Rounded.PhoneAndroid,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )

                    else -> devices.forEach { device ->
                        DeviceRows(
                            device = device,
                            busy = state.working,
                            onRevoke = { mode = DevicesMode.Revoke(device) },
                            onUnlink = { mode = DevicesMode.Unlink(device) },
                        )
                    }
                }
            }
        }
    }
}

private val ManagementNotice.isAboutDevices: Boolean
    get() = this == ManagementNotice.DeviceRevoked || this == ManagementNotice.DeviceUnlinked

/** One phone: whose it is, what it may do, and the two ways to take that away. */
@Composable
private fun DeviceRows(
    device: ManagedDevice,
    busy: Boolean,
    onRevoke: () -> Unit,
    onUnlink: () -> Unit,
) {
    SheetSection(title = device.displayName())
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        GroupItem(
            title = device.owner ?: correctedString(R.string.admin_device_not_linked),
            subtitle = device.statusLine(),
            icon = Icons.Rounded.PhoneAndroid,
            // A switched-off phone loses its colour, because what matters about
            // it is that it is off and not whose it was.
            tone = if (device.revoked) neutralTone() else accentTone(device.id.toInt()),
        )
        // A revoked device has nothing left to revoke, and an unlinked one has
        // nothing to unlink — the server answers both with a no-op or a 409,
        // and a row that can only fail is a row not worth drawing.
        if (!device.revoked) {
            GroupItem(
                title = correctedString(R.string.admin_device_revoke),
                tone = errorTone(),
                enabled = !busy,
                onClick = onRevoke,
            )
        }
        if (device.linked) {
            GroupItem(
                title = correctedString(R.string.admin_device_unlink),
                icon = Icons.Rounded.LinkOff,
                tone = accentTone(4),
                enabled = !busy,
                onClick = onUnlink,
            )
        }
    }
}

/** The name the phone gave, or the id it was assigned. */
@Composable
private fun ManagedDevice.displayName(): String =
    deviceName ?: correctedString(R.string.admin_device_unnamed, id)

/** "редактор · был(а) 12.09 07:55", or whichever halves of that are known. */
@Composable
private fun ManagedDevice.statusLine(): String {
    val role = role?.roleName()
    val seen = lastSeenAt?.let { correctedString(R.string.admin_device_seen, it.asClassStamp()) }
        ?: correctedString(R.string.admin_device_never_seen)
    val revoked = correctedString(R.string.admin_device_revoked).takeIf { this.revoked }
    return listOfNotNull(revoked, role, seen).joinToString(" · ")
}

/** A yes-or-no sheet face, shared by the two device actions. */
@Composable
private fun Confirmation(
    title: String,
    message: String,
    confirmLabel: String,
    busy: Boolean,
    failure: ManageFailure?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    SheetSection(title = title)
    SheetNote(text = message)
    SheetFailure(failure = failure)
    SheetButtons(
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onCancel = onCancel,
        busy = busy,
        destructive = true,
    )
}

package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ManageRepository
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import kotlinx.coroutines.flow.update

/**
 * What the devices sheet does: the list under its switch, and revoking or unlinking a phone.
 *
 * Holds no state of its own; see [ManagementPlumbing].
 */
internal class DevicesActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
) {

    fun loadDevices(includeRevoked: Boolean = plumbing.state.value.showRevokedDevices) =
        plumbing.read({
            it.copy(
                showRevokedDevices = includeRevoked,
                devices = it.devices.copy(loading = true, failure = null),
            )
        }) {
            val result = repository.devices(includeRevoked)
            plumbing.finish(result) { current, value, failure ->
                // Dropped when the switch has moved on since this was asked.
                // «Показывать отключённые» is the one control on this page that
                // changes what a read *asks for*, so flipping it twice leaves
                // two answers racing, and the loser is the one the user changed
                // their mind about — it would list a revoked phone under a
                // switch that says revoked phones are hidden.
                if (current.showRevokedDevices != includeRevoked) {
                    current
                } else {
                    current.copy(devices = Remote(value ?: current.devices.value, false, failure))
                }
            }
        }

    fun revokeDevice(device: ManagedDevice) = plumbing.write {
        val result = repository.revokeDevice(device.id)
        result.onSuccess {
            plumbing.state.update { it.copy(notice = ManagementNotice.DeviceRevoked) }
            loadDevices()
        }
        result
    }

    fun unlinkDevice(device: ManagedDevice) = plumbing.write {
        val result = repository.unlinkDevice(device.id)
        result.onSuccess {
            plumbing.state.update { it.copy(notice = ManagementNotice.DeviceUnlinked) }
            loadDevices()
        }
        result
    }
}

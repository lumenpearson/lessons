package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.core.data.repository.ClassActionOwner
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Link answers and queued actions retain the displayed server, class and bearer together. */
internal class SettingsDeviceLink(
    private val deviceLinkRepository: DeviceLinkRepository,
    private val viewModelScope: CoroutineScope,
) {
    data class Answer(val owner: ClassActionOwner?, val state: DeviceLinkState)

    val deviceLink = MutableStateFlow(Answer(null, DeviceLinkState.Idle))
    private var owner: ClassActionOwner? = null
    private var generation = 0L
    private var accountPageOpen = false
    private var refreshWhenBound = false

    fun bindOwner(value: ClassActionOwner?) {
        if (value == owner) return
        owner = value
        generation++
        deviceLink.value = Answer(value, DeviceLinkState.Idle)
        if (value != null && (accountPageOpen || refreshWhenBound)) {
            refreshWhenBound = false
            refreshDeviceLink(value)
        }
    }

    fun openAccountPage(value: ClassActionOwner?) {
        accountPageOpen = true
        refreshDeviceLink(value)
    }

    fun closeAccountPage() {
        accountPageOpen = false
    }

    fun refreshDeviceLink(value: ClassActionOwner?) {
        if (value == null) {
            refreshWhenBound = true
            return
        }
        request(value) { deviceLinkRepository.refresh(it) }
    }

    fun unlinkDevice(value: ClassActionOwner?) {
        if (value != null) request(value) { deviceLinkRepository.unlink(it) }
    }

    private fun request(asked: ClassActionOwner, send: suspend (ClassActionOwner) -> Result<DeviceLink>) {
        if (asked != owner || deviceLink.value.state is DeviceLinkState.Loading) return
        val known = deviceLink.value.state.known
        val requestedGeneration = ++generation
        deviceLink.value = Answer(asked, DeviceLinkState.Loading(known))
        viewModelScope.launch {
            if (requestedGeneration != generation || asked != owner) return@launch
            val result = send(asked)
            if (requestedGeneration != generation || asked != owner) return@launch
            result.onSuccess {
                deviceLink.value = Answer(asked, DeviceLinkState.Ready(it))
                deviceLinkRepository.rememberRole(asked.classId, it.role)
            }.onFailure {
                deviceLink.value = Answer(asked, DeviceLinkState.Failed(it, known))
            }
        }
    }
}

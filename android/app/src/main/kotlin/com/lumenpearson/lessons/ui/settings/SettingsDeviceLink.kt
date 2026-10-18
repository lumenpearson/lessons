package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The Telegram link of this phone, as the class page shows it: asked for, and
 * undone.
 *
 * Owns [deviceLink]. One write to it is not here: the view model's `init`
 * resets it to [DeviceLinkState.Idle] when the class on screen changes, and
 * keeps that launch so that it keeps its place among the view model's two.
 * Launches in [viewModelScope], the view model's; it never makes a scope of its own.
 */
internal class SettingsDeviceLink(
    private val sessionRepository: SessionRepository,
    private val deviceLinkRepository: DeviceLinkRepository,
    private val viewModelScope: CoroutineScope,
) {

    val deviceLink = MutableStateFlow<DeviceLinkState>(DeviceLinkState.Idle)

    /**
     * Asks the server whether this phone is tied to a Telegram account.
     *
     * Called when the class page opens, and again whenever the class on it
     * changes. Cheap to repeat: the server hands out the same link code until
     * it is used, so opening the page twice does not invalidate the code the
     * user is halfway through typing into the bot.
     */
    fun refreshDeviceLink() {
        if (deviceLink.value is DeviceLinkState.Loading) return
        val known = deviceLink.value.known
        deviceLink.value = DeviceLinkState.Loading(known)
        viewModelScope.launch {
            val asked = sessionRepository.current()?.classId
            val result = deviceLinkRepository.refresh()
            // A reply about the class the user has just switched away from is
            // not a slow answer, it is the wrong one. The request carried that
            // class's bearer, so nothing about it describes the class now on
            // screen; the switch has already reset the state to Idle and the
            // refresh it triggered is the one that will answer.
            if (asked != sessionRepository.current()?.classId) return@launch
            result
                .onSuccess {
                    deviceLink.value = DeviceLinkState.Ready(it)
                    asked?.let { id -> deviceLinkRepository.rememberRole(id, it.role) }
                }
                .onFailure { deviceLink.value = DeviceLinkState.Failed(it, known) }
        }
    }

    /** Unties the phone from its Telegram account; read-only afterwards. */
    fun unlinkDevice() {
        val known = deviceLink.value.known
        deviceLink.value = DeviceLinkState.Loading(known)
        viewModelScope.launch {
            val classId = sessionRepository.current()?.classId
            deviceLinkRepository.unlink()
                .onSuccess {
                    deviceLink.value = DeviceLinkState.Ready(it)
                    classId?.let { id -> deviceLinkRepository.rememberRole(id, it.role) }
                }
                .onFailure { deviceLink.value = DeviceLinkState.Failed(it, known) }
        }
    }
}

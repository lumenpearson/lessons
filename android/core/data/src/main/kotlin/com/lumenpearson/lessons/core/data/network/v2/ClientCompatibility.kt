package com.lumenpearson.lessons.core.data.network.v2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide: a refusal received by a worker survives until the next Activity. */
object ClientCompatibility {
    private val latch = CompatibilityLatch()
    val updateRequired: StateFlow<Boolean> = latch.updateRequired
    internal fun record(reason: String?) = latch.record(reason)
    fun claimPrompt(): Boolean = latch.claimPrompt()
}

internal class CompatibilityLatch {
    private val required = MutableStateFlow(false)
    val updateRequired: StateFlow<Boolean> = required.asStateFlow()
    private var claimed = false

    fun record(reason: String?) {
        if (reason == "CLIENT_TOO_OLD") required.value = true
    }

    @Synchronized
    fun claimPrompt(): Boolean {
        val available = required.value && !claimed
        if (available) claimed = true
        return available
    }
}

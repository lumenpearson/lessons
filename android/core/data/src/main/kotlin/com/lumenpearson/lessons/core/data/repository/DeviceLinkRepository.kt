package com.lumenpearson.lessons.core.data.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * The tie between this phone and a Telegram account.
 *
 * Nothing about it is stored on the device: the server is the only place that
 * knows whether the link exists, because the link is a fact about the token
 * and the token lives in the server's table. What the app keeps is the last
 * answer, so a settings page can draw the current state without a round trip
 * and refresh it when it opens.
 */
interface DeviceLinkRepository {

    /** The last answer from the server, or `null` before the first one. */
    val link: StateFlow<DeviceLink?>

    /**
     * Asks the server. On success [link] is updated and the value returned;
     * on failure [link] is left as it was, so a flaky network does not make a
     * linked device look unlinked.
     */
    suspend fun refresh(): Result<DeviceLink>

    /** Unties the device. Read-only afterwards, with a fresh code on the next [refresh]. */
    suspend fun unlink(): Result<DeviceLink>
}

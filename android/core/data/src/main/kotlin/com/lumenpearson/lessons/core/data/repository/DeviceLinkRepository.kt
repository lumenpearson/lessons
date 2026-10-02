package com.lumenpearson.lessons.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The tie between this phone and a Telegram account.
 *
 * Nothing about it is stored on the device: the server is the only place that
 * knows whether the link exists, because the link is a fact about the token
 * and the token lives in the server's table. What the app keeps is the last
 * answer, so a settings page can draw the current state without a round trip
 * and refresh it when it opens — and, since #228, the last *role* per class on
 * disk, because a role known only in memory is unknown at every launch, and
 * that is what decided whether the settings root drew its debug button.
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

    /** The role the server last gave this phone in [classId], from disk; see [rememberRole]. */
    fun lastRole(classId: Long): Flow<ClassRole?> = flowOf(null)

    /** Keeps [role] as [classId]'s last answer, for [lastRole] to draw before the next one. */
    suspend fun rememberRole(classId: Long, role: ClassRole?) = Unit
}

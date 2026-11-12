package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import kotlinx.coroutines.sync.withLock

/** Network I/O stays outside the gate; only ownership checks and their persistence serialize. */
internal class ScheduleMembership(
    private val preferences: LessonsPreferences,
    private val onRejected: suspend (RequestCredentials) -> Boolean,
) {
    data class Owner(val classId: Long, val credentials: RequestCredentials)

    suspend fun capture(): Owner? = preferences.membershipChanges.withLock {
        val (member, credentials) = preferences.currentClassRequest()
        member?.let { Owner(it.classId, credentials) }
    }

    suspend fun apply(
        sent: RequestCredentials?,
        classId: Long,
        persist: suspend () -> SyncResult,
    ): SyncResult = preferences.membershipChanges.withLock {
        val current = sent?.let { preferences.membershipFor(it) }
        if (current?.classId != classId) SyncResult.Failed("The membership changed during sync") else persist()
    }

    suspend fun reject(sent: RequestCredentials?): Boolean = sent?.let { onRejected(it) } ?: false
}

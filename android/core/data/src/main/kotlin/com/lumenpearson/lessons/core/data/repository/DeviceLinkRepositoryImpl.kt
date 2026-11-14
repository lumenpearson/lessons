package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.MeRemote
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * A complete v2 linking operation, and a memory of its last owned answer.
 *
 * Failures come back as [Result] rather than thrown, for the same reason the
 * join call does: the settings page shows them verbatim and has nothing to
 * decide. Token rejection belongs to the sync path; this class does not sign
 * anybody out, including on a bare 401.
 */
internal class DeviceLinkRepositoryImpl(
    private val remote: MeRemote,
    private val preferences: LessonsPreferences? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeviceLinkRepository {

    private val latest = MutableStateFlow<DeviceLink?>(null)
    // Guarded with the store membership gate, including the later role write.
    private var roleAnswer: RoleAnswer? = null
    override val link: StateFlow<DeviceLink?> = latest.asStateFlow()

    override suspend fun refresh(): Result<DeviceLink> = call { remote.refresh(it) }

    override suspend fun unlink(): Result<DeviceLink> = call { remote.unlink(it) }

    override fun lastRole(classId: Long): Flow<ClassRole?> =
        preferences?.lastRole(classId) ?: flowOf(null)

    override suspend fun rememberRole(classId: Long, role: ClassRole?) {
        val store = preferences ?: return
        store.membershipChanges.withLock {
            val answer = roleAnswer ?: return@withLock
            val current = store.currentClassRequest()
            val replyMatches = answer.classId == classId && answer.role == role
            val requestMatches = current.first?.classId == classId &&
                current.second.baseUrl == answer.credentials.baseUrl &&
                current.second.classToken == answer.credentials.classToken
            if (replyMatches && requestMatches) store.rememberRole(classId, role)
        }
    }

    private suspend fun call(block: suspend (CallObservation) -> DeviceLink): Result<DeviceLink> =
        withContext(ioDispatcher) {
            try {
                val store = preferences
                val owner = store?.membershipChanges?.withLock { store.currentClassRequest() }
                val value = block(CallObservation(owner?.second))
                if (store == null) {
                    latest.value = value
                } else store.membershipChanges.withLock {
                    val current = store.currentClassRequest()
                    val sent = owner?.second ?: throw IOException("The membership changed during linking")
                    if (current.first?.classId != owner?.first?.classId ||
                        current.second.baseUrl != sent.baseUrl || current.second.classToken != sent.classToken
                    ) throw IOException("The membership changed during linking")
                    roleAnswer = current.first?.let { RoleAnswer(it.classId, sent, value.role) }
                    latest.value = value
                }
                Result.success(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    private class RoleAnswer(val classId: Long, val credentials: RequestCredentials, val role: ClassRole?)
}

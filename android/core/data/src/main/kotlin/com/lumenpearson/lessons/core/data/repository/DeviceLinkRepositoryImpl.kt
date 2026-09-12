package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.DeviceMeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Two calls over [LessonsApi], and a memory of the last answer.
 *
 * Failures come back as [Result] rather than thrown, for the same reason the
 * join call does: the settings page shows them verbatim and has nothing to
 * decide. A 401 here means the token itself is gone, and the sync worker will
 * report that on its own; this class does not sign anybody out.
 */
internal class DeviceLinkRepositoryImpl(
    private val api: LessonsApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeviceLinkRepository {

    private val latest = MutableStateFlow<DeviceLink?>(null)
    override val link: StateFlow<DeviceLink?> = latest.asStateFlow()

    override suspend fun refresh(): Result<DeviceLink> = call { api.me().toDomain() }

    override suspend fun unlink(): Result<DeviceLink> = call {
        api.unlink()
        // The server's answer to unlink says only "not linked"; the code that
        // replaces the link is minted by the next /me, so ask for it now
        // rather than showing a card with nothing on it.
        api.me().toDomain()
    }

    private suspend fun call(block: suspend () -> DeviceLink): Result<DeviceLink> =
        withContext(ioDispatcher) {
            try {
                val value = block()
                latest.value = value
                Result.success(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
}

private fun DeviceMeDto.toDomain(): DeviceLink = DeviceLink(
    deviceName = deviceName?.trim()?.ifBlank { null },
    linked = linked,
    role = ClassRole.fromWire(role),
    canEdit = canEdit,
    linkCode = linkCode?.trim()?.ifBlank { null },
    botDeepLink = botDeepLink?.trim()?.ifBlank { null },
)

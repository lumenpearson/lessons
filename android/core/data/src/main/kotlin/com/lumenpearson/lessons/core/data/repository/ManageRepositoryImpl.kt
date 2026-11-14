package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.ManageRemote
import com.lumenpearson.lessons.core.data.network.v2.RemoteError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Classifies domain-valued management calls without caching another copy of the class. */
internal class ManageRepositoryImpl(
    private val api: ManageRemote,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val membership: ScheduleMembership? = null,
) : ManageRepository {
    override suspend fun classCard() = call { api.classCard(it) }

    override suspend fun updateClass(before: ManagedClass, edit: ClassEdit) = call { api.updateClass(before, edit, it) }

    override suspend fun setJoinMode(mode: ClassJoinMode) = call { api.setJoinMode(mode, it) }

    override suspend fun deleteClass(confirmName: String) = call { api.deleteClass(confirmName, it) }

    override suspend fun searchSchools(query: String) = call { api.searchSchools(query, it) }

    override suspend fun subjects() = call { api.subjects(it) }

    override suspend fun createSubject(form: SubjectForm) = call { api.createSubject(form, it) }

    override suspend fun updateSubject(before: ManagedSubject, form: SubjectForm) =
        call { api.updateSubject(before, form, it) }

    override suspend fun deleteSubject(id: Long) = call { api.deleteSubject(id, it) }

    override suspend fun bells() = call { api.bells(it) }

    override suspend fun createBellSchedule(name: String, periods: List<BellPeriod>) =
        call { api.createBellSchedule(name, periods, it) }

    override suspend fun renameBellSchedule(id: Long, name: String) = call { api.renameBellSchedule(id, name, it) }

    override suspend fun makeBellScheduleDefault(id: Long) = call { api.makeBellScheduleDefault(id, it) }

    override suspend fun writeBellPeriods(
        id: Long,
        periods: List<BellPeriod>,
    ) = call { api.writeBellPeriods(id, periods, it) }

    override suspend fun deleteBellSchedule(id: Long) = call { api.deleteBellSchedule(id, it) }

    override suspend fun timetable() = call { api.timetable(it) }

    override suspend fun importTimetable(
        text: String,
        replace: Boolean,
    ) = call { api.importTimetable(text, replace, it) }

    override suspend fun devices(includeRevoked: Boolean) = call { api.devices(includeRevoked, it) }

    override suspend fun revokeDevice(id: Long) = call { api.revokeDevice(id, it) }

    override suspend fun unlinkDevice(id: Long) = call { api.unlinkDevice(id, it) }

    override suspend fun log(pageSize: Int, pageToken: String) =
        call { api.log(pageSize.coerceIn(1, ManageRepository.LOG_PAGE_MAX), pageToken, it) }

    override suspend fun stats() = call { api.stats(it) }

    override suspend fun requests() = call { api.requests(it) }

    override suspend fun approveRequest(id: Long, role: ClassRole?) = call { api.approveRequest(id, role, it) }

    override suspend fun declineRequest(id: Long) = call { api.declineRequest(id, it) }

    private suspend fun <T> call(block: suspend (CallObservation) -> T): Result<T> = withContext(ioDispatcher) {
        var observation = CallObservation()
        try {
            val owner = membership?.capture()
            if (membership != null && owner == null) {
                throw IOException("There is no class membership for this operation")
            }
            observation = CallObservation(owner?.credentials)
            Result.success(block(observation))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A proxy status or an unobserved request cannot identify the membership to remove.
            if (failure is RemoteError && failure.reason == "DEVICE_TOKEN_INVALID") {
                runCatching { membership?.reject(observation.credentials) }.onFailure {
                    // Preserve cancellation; a failed disk write keeps the original refusal recoverable.
                    if (it is CancellationException) throw it
                }
            }
            Result.failure(ManageFailure.of(failure))
        }
    }
}

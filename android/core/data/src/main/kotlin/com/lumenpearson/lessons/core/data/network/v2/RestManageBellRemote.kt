package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellsWritten

internal class RestManageBellRemote(private val api: V2ManageBellApi) : ManageBellRemote {
    override suspend fun bells(observation: CallObservation) =
        RestRefusals.body(RestRefusals.request(observation) { api.list(it) }).schedules.map { it.toManagedBells() }

    override suspend fun createBellSchedule(name: String, periods: List<BellPeriod>, observation: CallObservation) =
        RestRefusals.request(observation) {
            api.create(BellScheduleDto(name = name.trim(), periods = periods.map { it.resource() }), it)
        }.let { response -> RestRefusals.body(response).schedule.requiredManagedBells(response.code()) }

    override suspend fun renameBellSchedule(id: Long, name: String, observation: CallObservation) =
        update(id, BellScheduleDto(name = name.trim()), "name", observation).schedule

    override suspend fun makeBellScheduleDefault(id: Long, observation: CallObservation) =
        update(id, BellScheduleDto(isDefault = true), "isDefault", observation)

    override suspend fun writeBellPeriods(id: Long, periods: List<BellPeriod>, observation: CallObservation) =
        update(id, BellScheduleDto(periods = periods.map { it.resource() }), "periods", observation)

    override suspend fun deleteBellSchedule(id: Long, observation: CallObservation) {
        val wireId = managementId(id)
        RestRefusals.body(RestRefusals.request(observation) { api.delete(wireId, it) })
    }

    private suspend fun update(
        id: Long,
        body: BellScheduleDto,
        mask: String,
        observation: CallObservation,
    ): BellsWritten {
        val wireId = managementId(id)
        val response = RestRefusals.request(observation) { api.update(wireId, body, mask, it) }
        val value = RestRefusals.body(response)
        return value.toManagedBellWrite(response.code())
    }
}

package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.google.protobuf.FieldMask
import com.lumenpearson.lessons.contract.v2.CreateBellScheduleRequest
import com.lumenpearson.lessons.contract.v2.CreateBellScheduleResponse
import com.lumenpearson.lessons.contract.v2.DeleteBellScheduleRequest
import com.lumenpearson.lessons.contract.v2.DeleteBellScheduleResponse
import com.lumenpearson.lessons.contract.v2.ListBellSchedulesRequest
import com.lumenpearson.lessons.contract.v2.ListBellSchedulesResponse
import com.lumenpearson.lessons.contract.v2.UpdateBellScheduleRequest
import com.lumenpearson.lessons.contract.v2.UpdateBellScheduleResponse
import com.lumenpearson.lessons.core.data.network.v2.dto.UpdateBellScheduleResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellsWritten

internal class RpcManageBellRemote(private val rpc: RpcCalls) : ManageBellRemote {
    override suspend fun bells(observation: CallObservation) =
        rpc.unary(List, ListBellSchedulesRequest.getDefaultInstance(), observation)
            .schedulesList.map { it.canonicalManagedBells().toManagedBells() }

    override suspend fun createBellSchedule(name: String, periods: List<BellPeriod>, observation: CallObservation) =
        rpc.unary(Create, CreateBellScheduleRequest.newBuilder().setSchedule(
            BellScheduleDto(name = name.trim(), periods = periods.map { it.resource() }).rpcManagedBells(),
        ).build(), observation).let { answer ->
            answer.schedule.takeIf { answer.hasSchedule() }?.canonicalManagedBells().requiredManagedBells()
        }

    override suspend fun renameBellSchedule(id: Long, name: String, observation: CallObservation) =
        update(id, BellScheduleDto(name = name.trim()), "name", observation).schedule

    override suspend fun makeBellScheduleDefault(id: Long, observation: CallObservation) =
        update(id, BellScheduleDto(isDefault = true), "is_default", observation)

    override suspend fun writeBellPeriods(id: Long, periods: List<BellPeriod>, observation: CallObservation) =
        update(id, BellScheduleDto(periods = periods.map { it.resource() }), "periods", observation)

    override suspend fun deleteBellSchedule(id: Long, observation: CallObservation) {
        rpc.unary(Delete, DeleteBellScheduleRequest.newBuilder().setScheduleId(managementId(id)).build(), observation)
    }

    private suspend fun update(
        id: Long,
        body: BellScheduleDto,
        mask: String,
        observation: CallObservation,
    ): BellsWritten {
        val request = UpdateBellScheduleRequest.newBuilder()
            .setSchedule(body.copy(id = managementId(id)).rpcManagedBells())
            .setUpdateMask(FieldMask.newBuilder().addPaths(mask)).build()
        val answer = rpc.unary(Update, request, observation)
        return UpdateBellScheduleResponseDto(
            answer.schedule.takeIf { answer.hasSchedule() }?.canonicalManagedBells(), answer.silencedLessons,
        ).toManagedBellWrite()
    }

    private companion object {
        val List = MethodSpec("lessons.v2.BellService/ListBellSchedules", ListBellSchedulesRequest::class,
            ListBellSchedulesResponse::class, StreamType.UNARY)
        val Create = MethodSpec("lessons.v2.BellService/CreateBellSchedule", CreateBellScheduleRequest::class,
            CreateBellScheduleResponse::class, StreamType.UNARY)
        val Update = MethodSpec("lessons.v2.BellService/UpdateBellSchedule", UpdateBellScheduleRequest::class,
            UpdateBellScheduleResponse::class, StreamType.UNARY)
        val Delete = MethodSpec("lessons.v2.BellService/DeleteBellSchedule", DeleteBellScheduleRequest::class,
            DeleteBellScheduleResponse::class, StreamType.UNARY)
    }
}

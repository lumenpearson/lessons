package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.GetTimetableRequest
import com.lumenpearson.lessons.contract.v2.GetTimetableResponse
import com.lumenpearson.lessons.contract.v2.ImportTimetableRequest
import com.lumenpearson.lessons.contract.v2.ImportTimetableResponse

internal class RpcManageTimetableRemote(private val rpc: RpcCalls) : ManageTimetableRemote {
    override suspend fun timetable(observation: CallObservation) =
        rpc.unary(Get, GetTimetableRequest.getDefaultInstance(), observation).let { answer ->
            if (!answer.hasTimetable()) throw malformedV2Payload("The response has no timetable")
            answer.timetable.canonicalManagedTimetable().toManagedTimetable()
        }

    override suspend fun importTimetable(text: String, replace: Boolean, observation: CallObservation) =
        rpc.unary(Import, ImportTimetableRequest.newBuilder().setText(text).setReplace(replace).build(), observation)
            .canonicalManagedImport().toManagedImport()

    private companion object {
        val Get = MethodSpec("lessons.v2.TimetableService/GetTimetable", GetTimetableRequest::class,
            GetTimetableResponse::class, StreamType.UNARY)
        val Import = MethodSpec("lessons.v2.TimetableService/ImportTimetable", ImportTimetableRequest::class,
            ImportTimetableResponse::class, StreamType.UNARY)
    }
}

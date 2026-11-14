package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.ImportTimetableRequestDto

internal class RestManageTimetableRemote(private val api: V2ManageTimetableApi) : ManageTimetableRemote {
    override suspend fun timetable(observation: CallObservation) =
        RestRefusals.request(observation) { api.get(it) }.let { response ->
            val value = RestRefusals.body(response).timetable
                ?: throw malformedV2Payload("The response has no timetable", response.code())
            value.toManagedTimetable()
        }

    override suspend fun importTimetable(text: String, replace: Boolean, observation: CallObservation) =
        RestRefusals.body(RestRefusals.request(observation) { api.apply(ImportTimetableRequestDto(text, replace), it) })
            .toManagedImport()
}

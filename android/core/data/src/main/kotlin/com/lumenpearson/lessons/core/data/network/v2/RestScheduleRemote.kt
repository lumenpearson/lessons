package com.lumenpearson.lessons.core.data.network.v2

import java.time.Clock

internal class RestScheduleRemote(private val api: V2Api, private val clock: Clock) : ScheduleRemote {
    override suspend fun window(openingYear: Int, ifNoneMatch: String?): ScheduleRemoteResult {
        return window(openingYear, ifNoneMatch, CallObservation())
    }

    override suspend fun window(
        openingYear: Int,
        ifNoneMatch: String?,
        observation: CallObservation,
    ): ScheduleRemoteResult {
        val response = RestRefusals.request(observation) { api.scheduleWindow(openingYear, ifNoneMatch, it) }
        val headerTag = response.headers()["ETag"]?.takeIf { it.isNotBlank() }
        if (response.code() == NotModifiedStatus) return ScheduleRemoteResult.NotModified(headerTag)
        val body = RestRefusals.body(response)
        val tag = headerTag ?: body.etag.takeIf { it.isNotBlank() }
        return if (body.notModified) ScheduleRemoteResult.NotModified(tag) else {
            val window = body.window ?: throw malformedV2Payload("The response has no schedule window", response.code())
            ScheduleRemoteResult.Changed(window.toTimetable(clock, response.code()), tag)
        }
    }

    private companion object {
        const val NotModifiedStatus = 304
    }
}

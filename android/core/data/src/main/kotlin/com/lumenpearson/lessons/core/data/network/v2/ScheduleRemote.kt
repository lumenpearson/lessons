package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.model.Timetable

internal fun interface ScheduleRemote {
    suspend fun window(openingYear: Int, ifNoneMatch: String?): ScheduleRemoteResult
}

internal sealed interface ScheduleRemoteResult {
    data class Changed(val timetable: Timetable, val etag: String?) : ScheduleRemoteResult
    data class NotModified(val etag: String?) : ScheduleRemoteResult
}

package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.toDomain
import com.lumenpearson.lessons.core.data.network.v2.RestRefusals
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemote
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemoteResult
import com.lumenpearson.lessons.core.model.SchoolYear
import java.time.Clock

/**
 * Existing cache tests use v1 DTO builders as data factories, not as a wire.
 * Capture a queried year before translating it for those builders; real v2
 * framing, paths and normalization are held independently by V2BindingsTest.
 */
internal fun LessonsApi.scheduleFixture(
    clock: Clock = Clock.systemUTC(),
    onYear: (Int) -> Unit = {},
): ScheduleRemote = ScheduleRemote { year, tag ->
    onYear(year)
    val response = bundle(SchoolYear.start(year).toString(), SchoolYear.daysOf(year), tag)
    val etag = response.headers()["ETag"]
    if (response.code() == NotModifiedStatus) ScheduleRemoteResult.NotModified(etag)
    else ScheduleRemoteResult.Changed(RestRefusals.body(response).toDomain(clock.millis()), etag)
}

private const val NotModifiedStatus = 304

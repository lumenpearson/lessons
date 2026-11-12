package com.lumenpearson.lessons.core.data.network.v2

import java.time.Clock
import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.GetScheduleWindowRequest
import com.lumenpearson.lessons.contract.v2.GetScheduleWindowResponse

internal class RpcScheduleRemote(private val rpc: RpcCalls, private val clock: Clock) : ScheduleRemote {
    override suspend fun window(openingYear: Int, ifNoneMatch: String?): ScheduleRemoteResult {
        val response = rpc.unary(Method, GetScheduleWindowRequest.newBuilder().setYear(openingYear).apply {
            if (ifNoneMatch != null) setIfNoneMatch(ifNoneMatch)
        }.build())
        val tag = response.etag.takeIf { it.isNotBlank() }
        return if (response.notModified) ScheduleRemoteResult.NotModified(tag) else {
            if (!response.hasWindow()) throw malformedV2Payload("The response has no schedule window")
            ScheduleRemoteResult.Changed(response.window.toTimetable(clock), tag)
        }
    }

    private companion object {
        val Method = MethodSpec("lessons.v2.ScheduleService/GetScheduleWindow", GetScheduleWindowRequest::class,
            GetScheduleWindowResponse::class, StreamType.UNARY)
    }
}

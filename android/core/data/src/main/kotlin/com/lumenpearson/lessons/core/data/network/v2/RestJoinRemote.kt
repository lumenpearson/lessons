package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDeviceRequestDto

internal class RestJoinRemote(private val api: V2Api) : JoinRemote {
    override suspend fun create(code: String, deviceName: String?): Session {
        val response = RestRefusals.request { api.createDevice(CreateDeviceRequestDto(code, deviceName), it) }
        return RestRefusals.body(response).toSession(response.code())
    }
}

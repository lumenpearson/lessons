package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.Session
import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.CreateDeviceRequest
import com.lumenpearson.lessons.contract.v2.CreateDeviceResponse

internal class RpcJoinRemote(private val rpc: RpcCalls) : JoinRemote {
    override suspend fun create(code: String, deviceName: String?): Session = rpc.unary(
        Method, CreateDeviceRequest.newBuilder().setCode(code).apply {
            if (deviceName != null) setDeviceName(deviceName)
        }.build(),
    ).toSession()

    private companion object {
        val Method = MethodSpec("lessons.v2.DeviceService/CreateDevice", CreateDeviceRequest::class,
            CreateDeviceResponse::class, StreamType.UNARY)
    }
}

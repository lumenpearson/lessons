package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.ListClassDevicesRequest
import com.lumenpearson.lessons.contract.v2.ListClassDevicesResponse
import com.lumenpearson.lessons.contract.v2.RevokeClassDeviceRequest
import com.lumenpearson.lessons.contract.v2.RevokeClassDeviceResponse
import com.lumenpearson.lessons.contract.v2.UnlinkClassDeviceRequest
import com.lumenpearson.lessons.contract.v2.UnlinkClassDeviceResponse

internal class RpcManageDeviceRemote(
    private val rpc: RpcCalls,
    private val classes: ManageClassRemote,
) : ManageDeviceRemote {
    override suspend fun devices(includeRevoked: Boolean, observation: CallObservation) =
        classes.readContext(observation).let { context ->
            rpc.unary(List, ListClassDevicesRequest.newBuilder().setIncludeRevoked(includeRevoked).build(),
                context.observation).devicesList.map { it.canonicalManagedDevice().toManagedDevice(context.zone) }
        }

    override suspend fun revokeDevice(id: Long, observation: CallObservation) = run {
        val wireId = managementId(id)
        val context = classes.readContext(observation)
        val answer = rpc.unary(Revoke, RevokeClassDeviceRequest.newBuilder().setDeviceId(wireId).build(),
            context.observation)
        if (!answer.hasDevice()) throw malformedV2Payload("The response has no device")
        answer.device.canonicalManagedDevice().toManagedDevice(context.zone)
    }

    override suspend fun unlinkDevice(id: Long, observation: CallObservation) = run {
        val wireId = managementId(id)
        val context = classes.readContext(observation)
        val answer = rpc.unary(Unlink, UnlinkClassDeviceRequest.newBuilder().setDeviceId(wireId).build(),
            context.observation)
        if (!answer.hasDevice()) throw malformedV2Payload("The response has no device")
        answer.device.canonicalManagedDevice().toManagedDevice(context.zone)
    }

    private companion object {
        val List = MethodSpec("lessons.v2.ClassDeviceService/ListClassDevices", ListClassDevicesRequest::class,
            ListClassDevicesResponse::class, StreamType.UNARY)
        val Revoke = MethodSpec("lessons.v2.ClassDeviceService/RevokeClassDevice", RevokeClassDeviceRequest::class,
            RevokeClassDeviceResponse::class, StreamType.UNARY)
        val Unlink = MethodSpec("lessons.v2.ClassDeviceService/UnlinkClassDevice", UnlinkClassDeviceRequest::class,
            UnlinkClassDeviceResponse::class, StreamType.UNARY)
    }
}

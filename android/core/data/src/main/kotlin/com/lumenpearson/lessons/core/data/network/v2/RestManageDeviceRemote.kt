package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.RevokeClassDeviceRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UnlinkClassDeviceRequestDto

internal class RestManageDeviceRemote(
    private val api: V2ManageDeviceApi,
    private val classes: ManageClassRemote,
) : ManageDeviceRemote {
    override suspend fun devices(includeRevoked: Boolean, observation: CallObservation) =
        classes.readContext(observation).let { context ->
            RestRefusals.body(RestRefusals.request(context.observation) { api.list(includeRevoked, it) })
                .devices.map { it.toManagedDevice(context.zone) }
        }

    override suspend fun revokeDevice(id: Long, observation: CallObservation) = run {
        val wireId = managementId(id)
        val context = classes.readContext(observation)
        val response = RestRefusals.request(context.observation) {
            api.revoke(wireId, RevokeClassDeviceRequestDto(), it)
        }
        val value = RestRefusals.body(response).device
            ?: throw malformedV2Payload("The response has no device", response.code())
        value.toManagedDevice(context.zone)
    }

    override suspend fun unlinkDevice(id: Long, observation: CallObservation) = run {
        val wireId = managementId(id)
        val context = classes.readContext(observation)
        val response = RestRefusals.request(context.observation) {
            api.unlink(wireId, UnlinkClassDeviceRequestDto(), it)
        }
        val value = RestRefusals.body(response).device
            ?: throw malformedV2Payload("The response has no device", response.code())
        value.toManagedDevice(context.zone)
    }
}

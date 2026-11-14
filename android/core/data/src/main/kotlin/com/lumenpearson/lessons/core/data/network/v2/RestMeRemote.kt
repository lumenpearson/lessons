package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.CreateLinkCodeRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.MeDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UnlinkMeRequestDto
import com.lumenpearson.lessons.core.data.repository.DeviceLink

internal class RestMeRemote(private val api: V2Api) : MeRemote {
    override suspend fun refresh(observation: CallObservation): DeviceLink {
        val body = RestRefusals.body(RestRefusals.request(observation) { api.getMe(it) })
        return linked(body.me, observation)
    }

    override suspend fun unlink(observation: CallObservation): DeviceLink {
        val body = RestRefusals.body(RestRefusals.request(observation) { api.unlinkMe(UnlinkMeRequestDto(), it) })
        return linked(body.me, observation)
    }

    private suspend fun linked(me: MeDto?, observation: CallObservation): DeviceLink {
        val value = me ?: throw malformedV2Payload("The response has no device identity")
        val link = deviceLink(value.deviceName, value.linked, value.role, value.canEdit)
        if (link.linked) return link
        val code = RestRefusals.body(RestRefusals.request(observation.followUp()) {
            api.createLinkCode(CreateLinkCodeRequestDto(), it)
        }).linkCode
        return link.withLinkCode(code?.code, code?.botDeepLink)
    }
}

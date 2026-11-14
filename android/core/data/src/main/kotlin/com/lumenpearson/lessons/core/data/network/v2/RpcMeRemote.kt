package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.CreateLinkCodeRequest
import com.lumenpearson.lessons.contract.v2.CreateLinkCodeResponse
import com.lumenpearson.lessons.contract.v2.GetMeRequest
import com.lumenpearson.lessons.contract.v2.GetMeResponse
import com.lumenpearson.lessons.contract.v2.Me
import com.lumenpearson.lessons.contract.v2.UnlinkMeRequest
import com.lumenpearson.lessons.contract.v2.UnlinkMeResponse
import com.lumenpearson.lessons.core.data.network.v2.dto.RoleWireSerializer
import com.lumenpearson.lessons.core.data.repository.DeviceLink

internal class RpcMeRemote(private val rpc: RpcCalls) : MeRemote {
    override suspend fun refresh(observation: CallObservation): DeviceLink {
        val answer = rpc.unary(Get, GetMeRequest.getDefaultInstance(), observation)
        return linked(answer.me.takeIf { answer.hasMe() }, observation)
    }

    override suspend fun unlink(observation: CallObservation): DeviceLink {
        val answer = rpc.unary(Unlink, UnlinkMeRequest.getDefaultInstance(), observation)
        return linked(answer.me.takeIf { answer.hasMe() }, observation)
    }

    private suspend fun linked(me: Me?, observation: CallObservation): DeviceLink {
        val value = me ?: throw malformedV2Payload("The response has no device identity")
        val link = deviceLink(value.deviceName, value.linked,
            RoleWireSerializer.wireName(value.roleValue), value.canEdit)
        if (link.linked) return link
        val answer = rpc.unary(CreateCode, CreateLinkCodeRequest.getDefaultInstance(), observation.followUp())
        val code = answer.linkCode.takeIf { answer.hasLinkCode() }
        return link.withLinkCode(code?.code, code?.botDeepLink)
    }

    private companion object {
        val Get = MethodSpec("lessons.v2.MeService/GetMe", GetMeRequest::class,
            GetMeResponse::class, StreamType.UNARY)
        val Unlink = MethodSpec("lessons.v2.MeService/UnlinkMe", UnlinkMeRequest::class,
            UnlinkMeResponse::class, StreamType.UNARY)
        val CreateCode = MethodSpec("lessons.v2.MeService/CreateLinkCode", CreateLinkCodeRequest::class,
            CreateLinkCodeResponse::class, StreamType.UNARY)
    }
}

package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.CreateDiarySessionRequest
import com.lumenpearson.lessons.contract.v2.CreateDiarySessionResponse
import com.lumenpearson.lessons.contract.v2.DeleteDiarySessionRequest
import com.lumenpearson.lessons.contract.v2.DeleteDiarySessionResponse
import com.lumenpearson.lessons.contract.v2.GetDiaryCapabilitiesRequest
import com.lumenpearson.lessons.contract.v2.GetDiaryCapabilitiesResponse
import com.lumenpearson.lessons.core.data.repository.DiaryCapabilities
import com.lumenpearson.lessons.core.data.repository.DiaryHandover

internal class RpcDiarySessionRemote(private val rpc: RpcCalls) : DiarySessionRemote {
    override suspend fun capabilities(): DiaryCapabilities {
        val answer = rpc.unary(Capabilities, GetDiaryCapabilitiesRequest.getDefaultInstance(), CallObservation())
        if (!answer.hasCapabilities()) throw malformedV2Payload("The response has no diary capabilities")
        val value = answer.capabilities
        return DiaryCapabilities(value.enabled, value.providersList.associate { it.provider to it.regionsList.toSet() })
    }

    override suspend fun registerSession(handover: DiaryHandover) =
        rpc.unary(Register, handover.rpcRequest(), CallObservation()).let { answer ->
            if (!answer.hasSession()) throw malformedV2Payload("The response has no diary session")
            answer.session.canonicalSession().toRegistration(handover.target)
        }

    override suspend fun logout() {
        rpc.unary(Logout, DeleteDiarySessionRequest.getDefaultInstance(), CallObservation())
    }

    private companion object {
        val Capabilities = MethodSpec("lessons.v2.DiaryService/GetDiaryCapabilities",
            GetDiaryCapabilitiesRequest::class, GetDiaryCapabilitiesResponse::class, StreamType.UNARY)
        val Register = MethodSpec("lessons.v2.DiaryService/CreateDiarySession",
            CreateDiarySessionRequest::class, CreateDiarySessionResponse::class, StreamType.UNARY)
        val Logout = MethodSpec("lessons.v2.DiaryService/DeleteDiarySession",
            DeleteDiarySessionRequest::class, DeleteDiarySessionResponse::class, StreamType.UNARY)
    }
}

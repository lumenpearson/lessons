package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.ApproveAccessRequestRequest
import com.lumenpearson.lessons.contract.v2.ApproveAccessRequestResponse
import com.lumenpearson.lessons.contract.v2.DeclineAccessRequestRequest
import com.lumenpearson.lessons.contract.v2.DeclineAccessRequestResponse
import com.lumenpearson.lessons.contract.v2.ListAccessRequestsRequest
import com.lumenpearson.lessons.contract.v2.ListAccessRequestsResponse
import com.lumenpearson.lessons.contract.v2.Role
import com.lumenpearson.lessons.core.data.network.v2.dto.RoleWireSerializer
import com.lumenpearson.lessons.core.data.network.v2.dto.ApproveAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeclineAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.RequestDecision

internal class RpcManageRequestRemote(
    private val rpc: RpcCalls,
    private val classes: ManageClassRemote,
) : ManageRequestRemote {
    override suspend fun requests(observation: CallObservation) = classes.readContext(observation).let { context ->
        rpc.unary(List, ListAccessRequestsRequest.getDefaultInstance(), context.observation)
            .accessRequestsList.map { it.canonicalManagedRequest().toManagedRequest(context.zone) }
    }

    override suspend fun approveRequest(id: Long, role: ClassRole?, observation: CallObservation): RequestDecision {
        val request = ApproveAccessRequestRequest.newBuilder().setRequestId(managementId(id))
            .setRole(role.rpcManagementRole()).build()
        val answer = rpc.unary(Approve, request, observation)
        return ApproveAccessRequestResponseDto(answer.requestId,
            RoleWireSerializer.wireName(answer.roleValue), answer.who).toManagedDecision()
    }

    override suspend fun declineRequest(id: Long, observation: CallObservation): RequestDecision {
        val answer = rpc.unary(Decline, DeclineAccessRequestRequest.newBuilder().setRequestId(managementId(id)).build(),
            observation)
        return DeclineAccessRequestResponseDto(answer.requestId, answer.who).toManagedDecision()
    }

    private companion object {
        val List = MethodSpec("lessons.v2.AccessRequestService/ListAccessRequests", ListAccessRequestsRequest::class,
            ListAccessRequestsResponse::class, StreamType.UNARY)
        val Approve = MethodSpec("lessons.v2.AccessRequestService/ApproveAccessRequest",
            ApproveAccessRequestRequest::class,
            ApproveAccessRequestResponse::class, StreamType.UNARY)
        val Decline = MethodSpec("lessons.v2.AccessRequestService/DeclineAccessRequest",
            DeclineAccessRequestRequest::class,
            DeclineAccessRequestResponse::class, StreamType.UNARY)
    }
}

private fun ClassRole?.rpcManagementRole(): Role = when (this) {
    ClassRole.VIEWER -> Role.ROLE_VIEWER
    ClassRole.EDITOR -> Role.ROLE_EDITOR
    ClassRole.ADMIN -> Role.ROLE_ADMIN
    ClassRole.OWNER -> Role.ROLE_OWNER
    null -> Role.ROLE_UNSPECIFIED
}

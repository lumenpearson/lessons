package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.ApproveAccessRequestRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeclineAccessRequestRequestDto
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.RequestDecision

internal class RestManageRequestRemote(
    private val api: V2ManageRequestApi,
    private val classes: ManageClassRemote,
) : ManageRequestRemote {
    override suspend fun requests(observation: CallObservation) = classes.readContext(observation).let { context ->
        RestRefusals.body(RestRefusals.request(context.observation) { api.list(it) })
            .accessRequests.map { it.toManagedRequest(context.zone) }
    }

    override suspend fun approveRequest(id: Long, role: ClassRole?, observation: CallObservation): RequestDecision {
        val wireId = managementId(id)
        val body = ApproveAccessRequestRequestDto(role = role?.let { "ROLE_${it.name}" }.orEmpty())
        val value = RestRefusals.body(RestRefusals.request(observation) { api.approve(wireId, body, it) })
        return value.toManagedDecision()
    }

    override suspend fun declineRequest(id: Long, observation: CallObservation): RequestDecision {
        val wireId = managementId(id)
        val value = RestRefusals.body(RestRefusals.request(observation) {
            api.decline(wireId, DeclineAccessRequestRequestDto(), it)
        })
        return value.toManagedDecision()
    }
}

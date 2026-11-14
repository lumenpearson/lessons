package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.AuditPage

internal class RestManageReportRemote(
    private val api: V2ManageReportApi,
    private val classes: ManageClassRemote,
) : ManageReportRemote {
    override suspend fun log(pageSize: Int, pageToken: String, observation: CallObservation): AuditPage {
        val context = classes.readContext(observation)
        val value = RestRefusals.body(RestRefusals.request(context.observation) {
            api.log(pageSize, pageToken.takeIf { it.isNotEmpty() }, it)
        })
        return AuditPage(value.auditEntries.map { it.toManagedAudit(context.zone) }, value.nextPageToken)
    }

    override suspend fun stats(observation: CallObservation) =
        RestRefusals.request(observation) { api.stats(it) }.let { response ->
            val value = RestRefusals.body(response).stats
                ?: throw malformedV2Payload("The response has no class statistics", response.code())
            value.toManagedStats()
        }
}

package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.GetClassStatsRequest
import com.lumenpearson.lessons.contract.v2.GetClassStatsResponse
import com.lumenpearson.lessons.contract.v2.ListAuditEntriesRequest
import com.lumenpearson.lessons.contract.v2.ListAuditEntriesResponse
import com.lumenpearson.lessons.core.data.repository.AuditPage

internal class RpcManageReportRemote(
    private val rpc: RpcCalls,
    private val classes: ManageClassRemote,
) : ManageReportRemote {
    override suspend fun log(pageSize: Int, pageToken: String, observation: CallObservation): AuditPage {
        val context = classes.readContext(observation)
        val answer = rpc.unary(Log, ListAuditEntriesRequest.newBuilder().setPageSize(pageSize).setPageToken(pageToken)
            .build(), context.observation)
        return AuditPage(answer.auditEntriesList.map { it.canonicalManagedAudit().toManagedAudit(context.zone) },
            answer.nextPageToken)
    }

    override suspend fun stats(observation: CallObservation) =
        rpc.unary(Stats, GetClassStatsRequest.getDefaultInstance(), observation).let { answer ->
            if (!answer.hasStats()) throw malformedV2Payload("The response has no class statistics")
            answer.stats.canonicalManagedStats().toManagedStats()
        }

    private companion object {
        val Log = MethodSpec("lessons.v2.AuditService/ListAuditEntries", ListAuditEntriesRequest::class,
            ListAuditEntriesResponse::class, StreamType.UNARY)
        val Stats = MethodSpec("lessons.v2.ClassService/GetClassStats", GetClassStatsRequest::class,
            GetClassStatsResponse::class, StreamType.UNARY)
    }
}

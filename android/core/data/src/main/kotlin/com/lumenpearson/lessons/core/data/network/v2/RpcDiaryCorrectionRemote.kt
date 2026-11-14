package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.ListCorrectionsRequest
import com.lumenpearson.lessons.contract.v2.ListCorrectionsResponse
import com.lumenpearson.lessons.contract.v2.BatchUpdateCorrectionsRequest
import com.lumenpearson.lessons.contract.v2.BatchUpdateCorrectionsResponse
import com.lumenpearson.lessons.contract.v2.ResetCorrectionsRequest
import com.lumenpearson.lessons.contract.v2.ResetCorrectionsResponse
import com.lumenpearson.lessons.contract.v2.ClearCorrectionsRequest
import com.lumenpearson.lessons.contract.v2.ClearCorrectionsResponse
import com.lumenpearson.lessons.contract.v2.CorrectionKey
import com.lumenpearson.lessons.contract.v2.CorrectionUpdate
import com.lumenpearson.lessons.core.data.repository.DiaryField

internal class RpcDiaryCorrectionRemote(private val rpc: RpcCalls) : DiaryCorrectionRemote {
    override suspend fun overrides(studentId: Long, observation: CallObservation) =
        rpc.unary(List, ListCorrectionsRequest.newBuilder().setStudentId(studentId).build(), observation)
            .correctionsList.mapNotNull { it.canonicalCorrection().toDomain() }

    override suspend fun correct(
        studentId: Long,
        target: String,
        field: DiaryField,
        value: String,
        original: String?,
        observation: CallObservation,
    ) {
        val correction = CorrectionUpdate.newBuilder().setTarget(target).setField(field.wire()).setValue(value).apply {
            original?.let { setOriginal(it) }
        }.build()
        val request = BatchUpdateCorrectionsRequest.newBuilder().setStudentId(studentId)
            .addCorrections(correction).build()
        rpc.unary(Update, request, observation)
    }

    override suspend fun reset(
        studentId: Long,
        target: String,
        field: DiaryField,
        observation: CallObservation,
    ) {
        val request = ResetCorrectionsRequest.newBuilder().setStudentId(studentId)
            .addCorrections(CorrectionKey.newBuilder().setTarget(target).setField(field.wire())).build()
        rpc.unary(Reset, request, observation)
    }

    override suspend fun resetAll(studentId: Long, observation: CallObservation) {
        rpc.unary(Clear, ClearCorrectionsRequest.newBuilder().setStudentId(studentId).build(), observation)
    }

    private companion object {
        val List = MethodSpec("lessons.v2.DiaryService/ListCorrections",
            ListCorrectionsRequest::class, ListCorrectionsResponse::class, StreamType.UNARY)
        val Update = MethodSpec("lessons.v2.DiaryService/BatchUpdateCorrections",
            BatchUpdateCorrectionsRequest::class, BatchUpdateCorrectionsResponse::class, StreamType.UNARY)
        val Reset = MethodSpec("lessons.v2.DiaryService/ResetCorrections",
            ResetCorrectionsRequest::class, ResetCorrectionsResponse::class, StreamType.UNARY)
        val Clear = MethodSpec("lessons.v2.DiaryService/ClearCorrections",
            ClearCorrectionsRequest::class, ClearCorrectionsResponse::class, StreamType.UNARY)
    }
}

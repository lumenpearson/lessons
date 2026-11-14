package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.google.protobuf.FieldMask
import com.lumenpearson.lessons.contract.v2.CreateSubjectRequest
import com.lumenpearson.lessons.contract.v2.CreateSubjectResponse
import com.lumenpearson.lessons.contract.v2.DeleteSubjectRequest
import com.lumenpearson.lessons.contract.v2.DeleteSubjectResponse
import com.lumenpearson.lessons.contract.v2.GetSubjectRequest
import com.lumenpearson.lessons.contract.v2.GetSubjectResponse
import com.lumenpearson.lessons.contract.v2.ListSubjectsRequest
import com.lumenpearson.lessons.contract.v2.ListSubjectsResponse
import com.lumenpearson.lessons.contract.v2.UpdateSubjectRequest
import com.lumenpearson.lessons.contract.v2.UpdateSubjectResponse
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import com.lumenpearson.lessons.core.data.repository.SubjectSaved

internal class RpcManageSubjectRemote(private val rpc: RpcCalls) : ManageSubjectRemote {
    override suspend fun subjects(observation: CallObservation) =
        rpc.unary(List, ListSubjectsRequest.getDefaultInstance(), observation)
            .subjectsList.map { it.canonicalManagedSubject().toManagedSubject() }

    override suspend fun createSubject(form: SubjectForm, observation: CallObservation): SubjectSaved {
        val answer = rpc.unary(Create, CreateSubjectRequest.newBuilder().setSubject(form.resource().rpcManagedSubject())
            .build(), observation)
        return SubjectSaved(answer.subject.takeIf { answer.hasSubject() }?.canonicalManagedSubject()
            .requiredManagedSubject(), 0)
    }

    override suspend fun updateSubject(
        before: ManagedSubject,
        form: SubjectForm,
        observation: CallObservation,
    ): SubjectSaved {
        val mutation = form.mutation(before)
        if (mutation.paths.isEmpty()) {
            val answer = rpc.unary(Get, GetSubjectRequest.newBuilder().setSubjectId(mutation.resource.id).build(),
                observation)
            return SubjectSaved(answer.subject.takeIf { answer.hasSubject() }?.canonicalManagedSubject()
                .requiredManagedSubject(), 0)
        }
        val request = UpdateSubjectRequest.newBuilder().setSubject(mutation.resource.rpcManagedSubject())
            .setUpdateMask(FieldMask.newBuilder().addAllPaths(mutation.paths)).build()
        val answer = rpc.unary(Update, request, observation)
        return SubjectSaved(answer.subject.takeIf { answer.hasSubject() }?.canonicalManagedSubject()
            .requiredManagedSubject(), answer.moved)
    }

    override suspend fun deleteSubject(id: Long, observation: CallObservation) {
        rpc.unary(Delete, DeleteSubjectRequest.newBuilder().setSubjectId(managementId(id)).build(), observation)
    }

    private companion object {
        val List = MethodSpec("lessons.v2.SubjectService/ListSubjects", ListSubjectsRequest::class,
            ListSubjectsResponse::class, StreamType.UNARY)
        val Get = MethodSpec("lessons.v2.SubjectService/GetSubject", GetSubjectRequest::class,
            GetSubjectResponse::class, StreamType.UNARY)
        val Create = MethodSpec("lessons.v2.SubjectService/CreateSubject", CreateSubjectRequest::class,
            CreateSubjectResponse::class, StreamType.UNARY)
        val Update = MethodSpec("lessons.v2.SubjectService/UpdateSubject", UpdateSubjectRequest::class,
            UpdateSubjectResponse::class, StreamType.UNARY)
        val Delete = MethodSpec("lessons.v2.SubjectService/DeleteSubject", DeleteSubjectRequest::class,
            DeleteSubjectResponse::class, StreamType.UNARY)
    }
}

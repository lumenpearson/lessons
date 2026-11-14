package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.google.protobuf.FieldMask
import com.lumenpearson.lessons.contract.v2.DeleteClassRequest
import com.lumenpearson.lessons.contract.v2.DeleteClassResponse
import com.lumenpearson.lessons.contract.v2.GetClassRequest
import com.lumenpearson.lessons.contract.v2.GetClassResponse
import com.lumenpearson.lessons.contract.v2.ListSchoolsRequest
import com.lumenpearson.lessons.contract.v2.ListSchoolsResponse
import com.lumenpearson.lessons.contract.v2.UpdateClassRequest
import com.lumenpearson.lessons.contract.v2.UpdateClassResponse
import com.lumenpearson.lessons.core.data.network.v2.dto.ListSchoolsResponseDto
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ManagedClass

internal class RpcManageClassRemote(private val rpc: RpcCalls) : ManageClassRemote {
    override suspend fun classCard(observation: CallObservation): ManagedClass {
        val answer = rpc.unary(Get, GetClassRequest.getDefaultInstance(), observation)
        return answer.schoolClass.takeIf { answer.hasSchoolClass() }?.canonicalManagedClass().requiredManagedClass()
    }

    override suspend fun updateClass(
        before: ManagedClass,
        edit: ClassEdit,
        observation: CallObservation,
    ): ManagedClass {
        val mutation = edit.mutation(before)
        if (mutation.paths.isEmpty()) return classCard(observation)
        return update(mutation, observation)
    }

    override suspend fun setJoinMode(mode: ClassJoinMode, observation: CallObservation) =
        update(mode.mutation(), observation)

    override suspend fun deleteClass(confirmName: String, observation: CallObservation) {
        rpc.unary(Delete, DeleteClassRequest.newBuilder().setConfirmation(confirmName.trim()).build(), observation)
    }

    override suspend fun searchSchools(query: String, observation: CallObservation) =
        rpc.unary(Schools, ListSchoolsRequest.newBuilder().setQuery(query.trim()).setPageSize(SchoolLimit).build(),
            observation).let { answer ->
            ListSchoolsResponseDto(schools = answer.schoolsList.map { it.canonicalManagedSchool() },
                totalSize = answer.totalSize, truncated = answer.truncated).toManagedSchools()
        }

    private suspend fun update(mutation: ClassMutation, observation: CallObservation): ManagedClass {
        val request = UpdateClassRequest.newBuilder().setSchoolClass(mutation.resource.rpcManagedClass())
            .setUpdateMask(FieldMask.newBuilder().addAllPaths(mutation.paths)).build()
        val answer = rpc.unary(Update, request, observation)
        return answer.schoolClass.takeIf { answer.hasSchoolClass() }?.canonicalManagedClass().requiredManagedClass()
    }

    private companion object {
        const val SchoolLimit = 20
        val Get = MethodSpec("lessons.v2.ClassService/GetClass", GetClassRequest::class,
            GetClassResponse::class, StreamType.UNARY)
        val Update = MethodSpec("lessons.v2.ClassService/UpdateClass", UpdateClassRequest::class,
            UpdateClassResponse::class, StreamType.UNARY)
        val Delete = MethodSpec("lessons.v2.ClassService/DeleteClass", DeleteClassRequest::class,
            DeleteClassResponse::class, StreamType.UNARY)
        val Schools = MethodSpec("lessons.v2.DirectoryService/ListSchools", ListSchoolsRequest::class,
            ListSchoolsResponse::class, StreamType.UNARY)
    }
}

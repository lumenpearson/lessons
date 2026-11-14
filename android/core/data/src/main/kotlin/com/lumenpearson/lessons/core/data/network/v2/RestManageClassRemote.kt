package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ManagedClass

internal class RestManageClassRemote(
    private val api: V2ManageClassApi,
    private val directory: V2DirectoryApi,
) : ManageClassRemote {
    override suspend fun classCard(observation: CallObservation): ManagedClass {
        val response = RestRefusals.request(observation) { api.classCard(it) }
        return RestRefusals.body(response).schoolClass.requiredManagedClass(response.code())
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
        RestRefusals.body(RestRefusals.request(observation) { api.delete(confirmName.trim(), it) })
    }

    override suspend fun searchSchools(query: String, observation: CallObservation) =
        RestRefusals.body(RestRefusals.request(observation) { directory.schools(query.trim(), SchoolLimit, it) })
            .toManagedSchools()

    private suspend fun update(mutation: ClassMutation, observation: CallObservation): ManagedClass {
        val response = RestRefusals.request(observation) {
            api.update(mutation.resource, mutation.paths.fieldMaskQuery(), it)
        }
        return RestRefusals.body(response).schoolClass.requiredManagedClass(response.code())
    }

    private companion object { const val SchoolLimit = 20 }
}

package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import com.lumenpearson.lessons.core.data.repository.SubjectSaved

internal class RestManageSubjectRemote(private val api: V2ManageSubjectApi) : ManageSubjectRemote {
    override suspend fun subjects(observation: CallObservation) =
        RestRefusals.body(RestRefusals.request(observation) { api.list(it) }).subjects.map { it.toManagedSubject() }

    override suspend fun createSubject(form: SubjectForm, observation: CallObservation): SubjectSaved {
        val response = RestRefusals.request(observation) { api.create(form.resource(), it) }
        return SubjectSaved(RestRefusals.body(response).subject.requiredManagedSubject(response.code()), 0)
    }

    override suspend fun updateSubject(
        before: ManagedSubject,
        form: SubjectForm,
        observation: CallObservation,
    ): SubjectSaved {
        val mutation = form.mutation(before)
        val id = mutation.resource.id
        if (mutation.paths.isEmpty()) {
            val response = RestRefusals.request(observation) { api.get(id, it) }
            return SubjectSaved(RestRefusals.body(response).subject.requiredManagedSubject(response.code()), 0)
        }
        val response = RestRefusals.request(observation) {
            api.update(id, mutation.resource.copy(id = 0), mutation.paths.fieldMaskQuery(), it)
        }
        val body = RestRefusals.body(response)
        return SubjectSaved(body.subject.requiredManagedSubject(response.code()), body.moved)
    }

    override suspend fun deleteSubject(id: Long, observation: CallObservation) {
        val wireId = managementId(id)
        RestRefusals.body(RestRefusals.request(observation) { api.delete(wireId, it) })
    }
}

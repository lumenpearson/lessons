package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ManageRepository
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SubjectForm

/**
 * What the subjects sheet does: the list, and adding, editing and deleting one.
 *
 * Holds no state of its own; see [ManagementPlumbing].
 */
internal class SubjectsActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
) {

    fun loadSubjects() = plumbing.read({ it.copy(subjects = it.subjects.copy(loading = true, failure = null)) }) {
        val result = request(repository) { subjects() }
        finish(result) { current, value, failure ->
            current.copy(subjects = Remote(value ?: current.subjects.value, false, failure))
        }
    }

    fun addSubject(form: SubjectForm) = plumbing.write {
        val result = request(repository) { createSubject(form) }
        result.onCurrentSuccess { saved ->
            update { it.copy(notice = ManagementNotice.SubjectAdded(saved.subject.name)) }
            loadSubjects()
        }
        result
    }

    /**
     * Saves an edit, and says what a rename cost.
     *
     * [ManagementNotice.SubjectRenamed] is raised only when rows actually moved,
     * because «строк обновлено: 0» after changing a teacher's name would read as
     * a warning about something that did not happen.
     */
    fun saveSubject(before: ManagedSubject, form: SubjectForm) = plumbing.write {
        val result = request(repository) { updateSubject(before, form) }
        result.onCurrentSuccess { saved ->
            update {
                it.copy(
                    notice = if (saved.moved > 0) {
                        ManagementNotice.SubjectRenamed(saved.subject.name, saved.moved)
                    } else {
                        ManagementNotice.SubjectSaved(saved.subject.name)
                    },
                )
            }
            loadSubjects()
        }
        result
    }

    fun deleteSubject(subject: ManagedSubject) = plumbing.write {
        val result = request(repository) { deleteSubject(subject.id) }
        result.onCurrentSuccess {
            update { it.copy(notice = ManagementNotice.SubjectDeleted(subject.name)) }
            loadSubjects()
        }
        result
    }
}

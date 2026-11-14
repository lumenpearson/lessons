package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManageRepository

/**
 * What the statistics and requests sheets do: the class's numbers, the access
 * requests, and approving or declining one.
 *
 * Holds no state of its own; see [ManagementPlumbing].
 */
internal class RequestsActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
) {

    fun loadStats() = plumbing.read({ it.copy(stats = it.stats.copy(loading = true, failure = null)) }) {
        val result = request(repository) { stats() }
        finish(result) { current, value, failure ->
            current.copy(stats = Remote(value ?: current.stats.value, false, failure))
        }
    }

    fun loadRequests() = plumbing.read({ it.copy(requests = it.requests.copy(loading = true, failure = null)) }) {
        val result = request(repository) { requests() }
        finish(result) { current, value, failure ->
            current.copy(requests = Remote(value ?: current.requests.value, false, failure))
        }
    }

    fun approveRequest(request: AccessRequest, role: ClassRole?) = plumbing.write {
        val result = request(repository) { approveRequest(request.id, role) }
        result.onCurrentSuccess { decision ->
            update {
                it.copy(notice = ManagementNotice.RequestApproved(decision.who, decision.role))
            }
            loadRequests()
        }
        result
    }

    fun declineRequest(request: AccessRequest) = plumbing.write {
        val result = request(repository) { declineRequest(request.id) }
        result.onCurrentSuccess { decision ->
            update { it.copy(notice = ManagementNotice.RequestDeclined(decision.who)) }
            loadRequests()
        }
        result
    }
}

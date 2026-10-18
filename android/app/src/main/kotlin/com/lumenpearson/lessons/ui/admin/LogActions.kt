package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ManageRepository

/**
 * What the log sheet does: one page of the audit log at a time, forward and back.
 *
 * Holds no state of its own; see [ManagementPlumbing].
 */
internal class LogActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
) {

    fun loadLog(offset: Int = 0) =
        plumbing.read({ it.copy(log = it.log.copy(loading = true, failure = null)) }) {
            val result = repository.log(offset = offset)
            plumbing.finish(result) { current, value, failure ->
                current.copy(log = Remote(value ?: current.log.value, false, failure))
            }
        }

    /** One page forward, or one back; both are the same read at a new offset. */
    fun showNextLogPage() {
        val page = plumbing.state.value.log.value ?: return
        if (!page.hasMore) return
        loadLog(page.offset + page.limit)
    }

    fun showPreviousLogPage() {
        val page = plumbing.state.value.log.value ?: return
        if (page.offset <= 0) return
        loadLog((page.offset - page.limit).coerceAtLeast(0))
    }
}

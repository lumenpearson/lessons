package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ManageRepository

/** Navigation remembers successful token positions, never an inferred server offset or total. */
internal class LogActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
) {
    fun loadLog() = load(LogPosition(), emptyList())

    fun showNextLogPage() {
        val state = plumbing.state.value
        val page = state.log.value ?: return
        if (state.log.loading || !state.logNavigation.canAdvance(page)) return
        val navigation = state.logNavigation
        val next = LogPosition(page.nextPageToken, navigation.current.firstRow + page.entries.size)
        load(next, navigation.previous + navigation.current)
    }

    fun showPreviousLogPage() {
        val state = plumbing.state.value
        if (state.log.loading) return
        val previous = state.logNavigation.previous
        val target = previous.lastOrNull() ?: return
        load(target, previous.dropLast(1))
    }

    private fun load(position: LogPosition, previous: List<LogPosition>) {
        if (plumbing.state.value.log.loading) return
        plumbing.read({ it.copy(log = it.log.copy(loading = true, failure = null)) }) {
            val result = repository.log(pageToken = position.token)
            finish(result) { current, value, failure ->
                current.copy(
                    log = Remote(value ?: current.log.value, false, failure),
                    // A failed turn retains both the visible page and the path back to it.
                    logNavigation = if (value == null) current.logNavigation else LogNavigation(position, previous),
                )
            }
        }
    }
}

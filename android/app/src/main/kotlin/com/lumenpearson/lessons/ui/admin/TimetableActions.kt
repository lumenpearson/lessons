package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ManageRepository
import kotlinx.coroutines.flow.update

/**
 * What the import sheet does: the export it opens on, an import, and the
 * consent an import that would overwrite something waits for.
 *
 * Holds no state of its own; see [ManagementPlumbing].
 */
internal class TimetableActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
) {

    /**
     * Reads the export — and drops any consent that was waiting on it.
     *
     * This is the read «📥 Импорт» makes when it opens, and a [pendingImport] is
     * an agreement about a paste that lives in the sheet's own text box. The
     * box goes when the sheet does; the agreement used to stay, so reopening
     * the sheet landed on «Заменить» offering to delete a week on behalf of
     * text nowhere on screen. The two calls that import something clear it
     * themselves before reloading, so nothing live is thrown away here.
     */
    fun loadTimetable() = plumbing.read({
        it.copy(
            pendingImport = null,
            timetable = it.timetable.copy(loading = true, failure = null),
        )
    }) {
        val result = repository.timetable()
        plumbing.finish(result) { current, value, failure ->
            current.copy(timetable = Remote(value ?: current.timetable.value, false, failure))
        }
    }

    /**
     * The first tap: parse and apply, unless something would be overwritten.
     *
     * A refusal is not a failure and is not reported as one. `applied: false`
     * with conflicts is the preview the bot draws before «Применить», and it
     * lands in [PendingImport] so the sheet can ask.
     */
    fun importTimetable(text: String) = plumbing.write {
        val result = repository.importTimetable(text, replace = false)
        result.onSuccess { outcome ->
            plumbing.state.update {
                if (outcome.applied) {
                    it.copy(
                        pendingImport = null,
                        importRejected = outcome.rejected,
                        notice = ManagementNotice.Imported(
                            days = outcome.days.size,
                            lessons = outcome.lessons,
                            bells = outcome.bells,
                        ),
                    )
                } else {
                    it.copy(
                        pendingImport = PendingImport(text = text, conflicts = outcome.conflicts),
                        importRejected = outcome.rejected,
                    )
                }
            }
            if (outcome.applied) loadTimetable()
        }
        result
    }

    /** The second tap: the same text, with consent. */
    fun replacePendingImport() {
        val pending = plumbing.state.value.pendingImport ?: return
        plumbing.write {
            val result = repository.importTimetable(pending.text, replace = true)
            result.onSuccess { outcome ->
                plumbing.state.update {
                    it.copy(
                        pendingImport = null,
                        importRejected = outcome.rejected,
                        notice = ManagementNotice.Imported(
                            days = outcome.days.size,
                            lessons = outcome.lessons,
                            bells = outcome.bells,
                        ),
                    )
                }
                loadTimetable()
            }
            result
        }
    }

    /** Cancels it. Nothing was written, so there is nothing to undo. */
    fun cancelPendingImport() {
        plumbing.state.update { it.copy(pendingImport = null) }
    }
}

package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ManageRepository

/**
 * What the bells sheet does: the schedules, and adding, renaming, making the
 * default, rewriting and deleting one.
 *
 * Holds no state of its own; see [ManagementPlumbing].
 */
internal class BellsActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
) {

    fun loadBells() = plumbing.read({ it.copy(bells = it.bells.copy(loading = true, failure = null)) }) {
        val result = repository.bells()
        finish(result) { current, value, failure ->
            current.copy(bells = Remote(value ?: current.bells.value, false, failure))
        }
    }

    fun addBellSchedule(name: String, periods: List<BellPeriod>) = plumbing.write {
        val result = repository.createBellSchedule(name, periods)
        result.onCurrentSuccess { schedule ->
            update { it.copy(notice = ManagementNotice.BellsSaved(schedule.name)) }
            loadBells()
        }
        result
    }

    fun renameBellSchedule(id: Long, name: String) = plumbing.write {
        val result = repository.renameBellSchedule(id, name)
        result.onCurrentSuccess { schedule ->
            update { it.copy(notice = ManagementNotice.BellsSaved(schedule.name)) }
            loadBells()
        }
        result
    }

    fun makeBellScheduleDefault(id: Long) = plumbing.write {
        val result = repository.makeBellScheduleDefault(id)
        result.onCurrentSuccess { written ->
            update {
                it.copy(
                    notice = ManagementNotice.BellsDefault(
                        written.schedule.name,
                        written.silencedLessons,
                    ),
                )
            }
            loadBells()
        }
        result
    }

    fun saveBellPeriods(id: Long, periods: List<BellPeriod>) = plumbing.write {
        val result = repository.writeBellPeriods(id, periods)
        result.onCurrentSuccess { written ->
            update {
                it.copy(
                    notice = ManagementNotice.BellsSaved(
                        written.schedule.name,
                        written.silencedLessons,
                    ),
                )
            }
            loadBells()
        }
        result
    }

    fun deleteBellSchedule(schedule: BellSchedule) = plumbing.write {
        val result = repository.deleteBellSchedule(schedule.id)
        result.onCurrentSuccess {
            update { it.copy(notice = ManagementNotice.BellsDeleted(schedule.name)) }
            loadBells()
        }
        result
    }
}

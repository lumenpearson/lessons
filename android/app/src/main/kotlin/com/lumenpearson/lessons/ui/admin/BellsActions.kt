package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ManageRepository
import kotlinx.coroutines.flow.update

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
        plumbing.finish(result) { current, value, failure ->
            current.copy(bells = Remote(value ?: current.bells.value, false, failure))
        }
    }

    fun addBellSchedule(name: String, periods: List<BellPeriod>) = plumbing.write {
        val result = repository.createBellSchedule(name, periods)
        result.onSuccess { schedule ->
            plumbing.state.update { it.copy(notice = ManagementNotice.BellsSaved(schedule.name)) }
            loadBells()
        }
        result
    }

    fun renameBellSchedule(id: Long, name: String) = plumbing.write {
        val result = repository.renameBellSchedule(id, name)
        result.onSuccess { schedule ->
            plumbing.state.update { it.copy(notice = ManagementNotice.BellsSaved(schedule.name)) }
            loadBells()
        }
        result
    }

    fun makeBellScheduleDefault(id: Long) = plumbing.write {
        val result = repository.makeBellScheduleDefault(id)
        result.onSuccess { written ->
            plumbing.state.update {
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
        result.onSuccess { written ->
            plumbing.state.update {
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
        result.onSuccess {
            plumbing.state.update { it.copy(notice = ManagementNotice.BellsDeleted(schedule.name)) }
            loadBells()
        }
        result
    }
}

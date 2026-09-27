package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.diary.DiaryCache
import com.lumenpearson.lessons.core.data.diary.DiaryCachedMarks
import com.lumenpearson.lessons.core.data.diary.DiaryCachedPeriods
import com.lumenpearson.lessons.core.data.diary.DiaryCachedStudents
import com.lumenpearson.lessons.core.data.diary.DiaryCachedWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal val NoStudents = DiaryCachedStudents(emptyList(), loadedAt = null)

/**
 * A cache that holds nothing, for a view model built without one — the tests
 * that are about the network and nothing in front of it.
 */
internal object NoDiaryCache : DiaryCache {
    override val students: Flow<DiaryCachedStudents> = flowOf(NoStudents)
    override val selectedStudentId: Flow<Long?> = flowOf(null)
    override suspend fun selectStudent(id: Long?) = Unit
    override fun week(studentId: Long, monday: LocalDate): Flow<DiaryCachedWeek> =
        flowOf(DiaryCachedWeek(monday, emptyList(), emptyList(), null, null))
    override fun marks(studentId: Long): Flow<DiaryCachedMarks> =
        flowOf(DiaryCachedMarks(null, null, emptyList(), null))
    override fun periods(studentId: Long): Flow<DiaryCachedPeriods> =
        flowOf(DiaryCachedPeriods(emptyList(), null))
    override suspend fun clear() = Unit
}

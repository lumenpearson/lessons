package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.diary.DiaryCache
import com.lumenpearson.lessons.core.data.diary.DiaryCachedMarks
import com.lumenpearson.lessons.core.data.diary.DiaryCachedPeriods
import com.lumenpearson.lessons.core.data.diary.DiaryCachedStudents
import com.lumenpearson.lessons.core.data.diary.DiaryCachedWeek
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the phone saved, as the diary view model reads it — set by hand.
 *
 * The real cache is written only by the repository's reads (write-through),
 * and `:core:data`'s own tests hold that; what the screen's tests ask is what
 * the view model does with a save it is handed, so the save is arranged here
 * directly.
 */
internal class FakeDiaryCache : DiaryCache {

    val saved = MutableStateFlow(DiaryCachedStudents(emptyList(), loadedAt = null))
    val selected = MutableStateFlow<Long?>(null)
    val weeks = mutableMapOf<Pair<Long, LocalDate>, DiaryCachedWeek>()
    var savedMarks: DiaryCachedMarks = DiaryCachedMarks(null, null, emptyList(), null)
    var savedPeriods: DiaryCachedPeriods = DiaryCachedPeriods(emptyList(), null)

    fun saveStudents(students: List<DiaryStudent>, at: Instant) {
        saved.value = DiaryCachedStudents(students, at)
    }

    fun saveWeek(
        studentId: Long,
        monday: LocalDate,
        lessons: List<DiaryLesson>,
        at: Instant,
        homework: List<DiaryHomework> = emptyList(),
    ) {
        weeks[studentId to monday] = DiaryCachedWeek(monday, lessons, homework, at, at)
    }

    override val students: Flow<DiaryCachedStudents> = saved
    override val selectedStudentId: Flow<Long?> = selected

    override suspend fun selectStudent(id: Long?) {
        selected.value = id
    }

    override fun week(studentId: Long, monday: LocalDate): Flow<DiaryCachedWeek> =
        saved.map {
            weeks[studentId to monday] ?: DiaryCachedWeek(monday, emptyList(), emptyList(), null, null)
        }

    override fun marks(studentId: Long): Flow<DiaryCachedMarks> = flowOf(savedMarks)

    override fun periods(studentId: Long): Flow<DiaryCachedPeriods> = flowOf(savedPeriods)

    override suspend fun clear() {
        saved.value = DiaryCachedStudents(emptyList(), null)
        weeks.clear()
        selected.value = null
    }
}

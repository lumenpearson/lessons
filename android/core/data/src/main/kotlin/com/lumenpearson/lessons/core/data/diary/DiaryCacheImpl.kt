package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiarySessionStore
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DiaryCache] and its write side over [DiaryDao].
 *
 * @param dao resolved on first use, so building the container — which happens
 *   on every cold start, the widget's included — opens no database file.
 * @param selection where the chosen pupil is kept: the session's store.
 */
internal class DiaryCacheImpl(
    private val dao: () -> DiaryDao,
    private val selection: DiarySessionStore,
    private val clock: Clock = Clock.systemUTC(),
) : DiaryCache, DiaryCacheWriter {

    override val selectedStudentId: Flow<Long?> get() = selection.selectedStudentId

    override suspend fun selectStudent(id: Long?) = selection.selectStudent(id)

    /**
     * One lock for every write and for [clear], so a write cannot be checked
     * against the generation, overtaken by a clear, and then land.
     */
    private val writes = Mutex()
    private val current = AtomicLong(0)

    /**
     * Re-read on every change of any diary table, from a transactional
     * snapshot. Room's observed query emits once on collection, so a screen
     * gets what is there before anything is written. The database is opened on
     * collection, not when the flow is built.
     */
    private fun <T> observe(read: suspend (DiaryDao) -> T): Flow<T> =
        flow { emitAll(dao().observeChanges()) }
            .map { read(dao()) }
            .distinctUntilChanged()

    override val students: Flow<DiaryCachedStudents> = observe { dao ->
        val rows = dao.students()
        DiaryCachedStudents(
            students = rows.map { it.toDomain() },
            // Written together, so any row's stamp is the list's; the oldest is
            // the honest one if a later build ever writes them apart.
            loadedAt = rows.minOfOrNull { it.syncedAt }?.let(Instant::ofEpochMilli),
        )
    }

    override fun week(studentId: Long, monday: LocalDate): Flow<DiaryCachedWeek> = observe { dao ->
        val snapshot = dao.weekSnapshot(studentId, monday)
        DiaryCachedWeek(
            monday = monday,
            lessons = snapshot.lessons.map { it.toDomain() },
            homework = snapshot.homework.map { it.toDomain() },
            lessonsLoadedAt = snapshot.week?.lessonsSyncedAt?.let(Instant::ofEpochMilli),
            homeworkLoadedAt = snapshot.week?.homeworkSyncedAt?.let(Instant::ofEpochMilli),
        )
    }

    override fun marks(studentId: Long): Flow<DiaryCachedMarks> = observe { dao ->
        val snapshot = dao.marksSnapshot(studentId)
        val student = snapshot.student
        DiaryCachedMarks(
            from = student?.marksFrom,
            to = student?.marksTo,
            marks = snapshot.marks.map { it.toDomain() },
            loadedAt = student?.marksSyncedAt?.let(Instant::ofEpochMilli),
        )
    }

    override fun periods(studentId: Long): Flow<DiaryCachedPeriods> = observe { dao ->
        val snapshot = dao.periodsSnapshot(studentId)
        DiaryCachedPeriods(
            periods = snapshot.periods.map { it.toDomain() },
            loadedAt = snapshot.student?.periodsSyncedAt?.let(Instant::ofEpochMilli),
        )
    }

    override suspend fun clear() {
        writes.withLock {
            current.incrementAndGet()
            dao().clear()
        }
    }

    override fun generation(): Long = current.get()

    override suspend fun putStudents(generation: Long, students: List<DiaryStudent>) = write(generation) { at ->
        replaceStudents(students.mapIndexed { index, student -> student.toEntity(index, at) })
    }

    override suspend fun putLessons(
        generation: Long,
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        rows: List<DiaryLesson>,
    ) = write(generation) { at ->
        if (!DiaryWindows.isWholeWeeks(from, to)) return@write
        replaceLessons(studentId, from, to, rows.mapIndexed { index, row -> row.toEntity(studentId, index) }, at)
    }

    override suspend fun putHomework(
        generation: Long,
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        rows: List<DiaryHomework>,
    ) = write(generation) { at ->
        if (!DiaryWindows.isWholeWeeks(from, to)) return@write
        replaceHomework(studentId, from, to, rows.mapIndexed { index, row -> row.toEntity(studentId, index) }, at)
    }

    override suspend fun putMarks(
        generation: Long,
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        rows: List<DiaryMark>,
    ) = write(generation) { at ->
        replaceMarks(studentId, from, to, rows.mapIndexed { index, row -> row.toEntity(studentId, index) }, at)
    }

    override suspend fun putPeriods(generation: Long, studentId: Long, rows: List<DiaryPeriod>) =
        write(generation) { at ->
            replacePeriods(studentId, rows.mapIndexed { index, row -> row.toEntity(studentId, index) }, at)
        }

    private suspend fun write(generation: Long, block: suspend DiaryDao.(at: Long) -> Unit) {
        writes.withLock {
            // A clear happened since the read began: the answer is for an
            // account this phone no longer holds.
            if (generation != current.get()) return
            dao().block(clock.millis())
        }
    }
}

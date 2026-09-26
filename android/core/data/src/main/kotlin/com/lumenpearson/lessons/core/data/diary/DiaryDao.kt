package com.lumenpearson.lessons.core.data.diary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Every read and write of `diary.db`.
 *
 * An abstract class for the reason `TimetableDao` is one: the writes that must
 * not be seen half-done are concrete `@Transaction` methods, and the tests
 * drive exactly those bodies through `InMemoryDiaryDao`, which implements only
 * the abstract statements. A fake that re-implemented the orchestration could
 * agree with it while both were wrong.
 *
 * Two rules every statement below keeps, and `DiaryDaoPolicyTest` reads this
 * file to hold them:
 *
 *  * **every delete names its pupil** (or, in [clear], its whole table) — there
 *    are no foreign keys, so nothing is left to a cascade;
 *  * **no upsert syntax.** API 26–29 ship SQLite older than 3.24, which has no
 *    `ON CONFLICT … DO UPDATE`; a week is stamped with `INSERT OR IGNORE` and
 *    then an `UPDATE`, which every SQLite understands.
 */
@Dao
internal abstract class DiaryDao {

    // ---- reads ----------------------------------------------------------

    /**
     * Emits whenever any diary table changes, and carries nothing.
     *
     * Room re-runs an observed query when a table it names is written, so this
     * one names all six and returns no rows. The caches below read their
     * snapshot inside one transaction on each emission, rather than combining
     * three observed queries that could each land on a different side of a
     * write — a week's new lessons under its old «загружено в …».
     */
    @Query(
        "SELECT 1 FROM diary_student, diary_week, diary_period, diary_lesson, " +
            "diary_homework, diary_mark LIMIT 0",
    )
    abstract fun observeChanges(): Flow<List<Int>>

    @Query("SELECT * FROM diary_student ORDER BY position ASC")
    abstract suspend fun students(): List<DiaryStudentEntity>

    @Query("SELECT * FROM diary_student WHERE id = :studentId")
    abstract suspend fun student(studentId: Long): DiaryStudentEntity?

    @Query("SELECT * FROM diary_week WHERE student_id = :studentId AND monday = :monday")
    abstract suspend fun week(studentId: Long, monday: LocalDate): DiaryWeekEntity?

    @Query(
        "SELECT * FROM diary_lesson WHERE student_id = :studentId " +
            "AND date BETWEEN :from AND :to ORDER BY date ASC, position ASC",
    )
    abstract suspend fun lessons(studentId: Long, from: LocalDate, to: LocalDate): List<DiaryLessonEntity>

    @Query(
        "SELECT * FROM diary_homework WHERE student_id = :studentId " +
            "AND due_date BETWEEN :from AND :to ORDER BY due_date ASC, position ASC",
    )
    abstract suspend fun homework(studentId: Long, from: LocalDate, to: LocalDate): List<DiaryHomeworkEntity>

    @Query("SELECT * FROM diary_mark WHERE student_id = :studentId ORDER BY position ASC")
    abstract suspend fun marks(studentId: Long): List<DiaryMarkEntity>

    @Query("SELECT * FROM diary_period WHERE student_id = :studentId ORDER BY position ASC")
    abstract suspend fun periods(studentId: Long): List<DiaryPeriodEntity>

    /** One week of one pupil, read in one transaction; see [observeChanges]. */
    @Transaction
    open suspend fun weekSnapshot(studentId: Long, monday: LocalDate): DiaryWeekSnapshot {
        val sunday = monday.plusDays(6)
        return DiaryWeekSnapshot(
            week = week(studentId, monday),
            lessons = lessons(studentId, monday, sunday),
            homework = homework(studentId, monday, sunday),
        )
    }

    /** One pupil's marks and the window they cover, read together. */
    @Transaction
    open suspend fun marksSnapshot(studentId: Long): DiaryMarksSnapshot =
        DiaryMarksSnapshot(student = student(studentId), marks = marks(studentId))

    /** One pupil's terms and when they were read, together. */
    @Transaction
    open suspend fun periodsSnapshot(studentId: Long): DiaryPeriodsSnapshot =
        DiaryPeriodsSnapshot(student = student(studentId), periods = periods(studentId))

    // ---- building blocks: call the transactions below instead -------------

    /** `REPLACE` on the primary key, which SQLite has had for ever; not an upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertStudents(rows: List<DiaryStudentEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWeeksIfAbsent(rows: List<DiaryWeekEntity>)

    @Insert
    abstract suspend fun insertLessons(rows: List<DiaryLessonEntity>)

    @Insert
    abstract suspend fun insertHomework(rows: List<DiaryHomeworkEntity>)

    @Insert
    abstract suspend fun insertMarks(rows: List<DiaryMarkEntity>)

    @Insert
    abstract suspend fun insertPeriods(rows: List<DiaryPeriodEntity>)

    @Query(
        "UPDATE diary_week SET lessons_synced_at = :at " +
            "WHERE student_id = :studentId AND monday IN (:mondays)",
    )
    abstract suspend fun stampLessons(studentId: Long, mondays: List<LocalDate>, at: Long)

    @Query(
        "UPDATE diary_week SET homework_synced_at = :at " +
            "WHERE student_id = :studentId AND monday IN (:mondays)",
    )
    abstract suspend fun stampHomework(studentId: Long, mondays: List<LocalDate>, at: Long)

    @Query(
        "UPDATE diary_student SET marks_from = :from, marks_to = :to, marks_synced_at = :at " +
            "WHERE id = :studentId",
    )
    abstract suspend fun stampMarks(studentId: Long, from: LocalDate, to: LocalDate, at: Long)

    @Query("UPDATE diary_student SET periods_synced_at = :at WHERE id = :studentId")
    abstract suspend fun stampPeriods(studentId: Long, at: Long)

    @Query("DELETE FROM diary_student WHERE id NOT IN (:keep)")
    abstract suspend fun deleteStudentsOtherThan(keep: List<Long>)

    @Query("DELETE FROM diary_week WHERE student_id NOT IN (:keep)")
    abstract suspend fun deleteWeeksOtherThan(keep: List<Long>)

    @Query("DELETE FROM diary_period WHERE student_id NOT IN (:keep)")
    abstract suspend fun deletePeriodsOtherThan(keep: List<Long>)

    @Query("DELETE FROM diary_lesson WHERE student_id NOT IN (:keep)")
    abstract suspend fun deleteLessonsOtherThan(keep: List<Long>)

    @Query("DELETE FROM diary_homework WHERE student_id NOT IN (:keep)")
    abstract suspend fun deleteHomeworkOtherThan(keep: List<Long>)

    @Query("DELETE FROM diary_mark WHERE student_id NOT IN (:keep)")
    abstract suspend fun deleteMarksOtherThan(keep: List<Long>)

    @Query("DELETE FROM diary_lesson WHERE student_id = :studentId AND date BETWEEN :from AND :to")
    abstract suspend fun deleteLessons(studentId: Long, from: LocalDate, to: LocalDate)

    @Query(
        "DELETE FROM diary_homework WHERE student_id = :studentId " +
            "AND due_date BETWEEN :from AND :to",
    )
    abstract suspend fun deleteHomework(studentId: Long, from: LocalDate, to: LocalDate)

    @Query("DELETE FROM diary_mark WHERE student_id = :studentId")
    abstract suspend fun deleteMarks(studentId: Long)

    @Query("DELETE FROM diary_period WHERE student_id = :studentId")
    abstract suspend fun deletePeriods(studentId: Long)

    @Query("DELETE FROM diary_student")
    abstract suspend fun deleteAllStudents()

    @Query("DELETE FROM diary_week")
    abstract suspend fun deleteAllWeeks()

    @Query("DELETE FROM diary_period")
    abstract suspend fun deleteAllPeriods()

    @Query("DELETE FROM diary_lesson")
    abstract suspend fun deleteAllLessons()

    @Query("DELETE FROM diary_homework")
    abstract suspend fun deleteAllHomework()

    @Query("DELETE FROM diary_mark")
    abstract suspend fun deleteAllMarks()

    // ---- the writes -------------------------------------------------------

    /**
     * The account's pupils, as the server just listed them.
     *
     * A pupil who is no longer listed takes every row of theirs along —
     * explicitly, table by table — and a pupil still listed keeps the stamps of
     * what was fetched for them: `REPLACE` would otherwise reset those to
     * «never», and a marks tab read a minute ago would say «не загружено».
     */
    @Transaction
    open suspend fun replaceStudents(rows: List<DiaryStudentEntity>) {
        if (rows.isEmpty()) {
            clear()
            return
        }
        val keep = rows.map { it.id }
        deleteMarksOtherThan(keep)
        deleteHomeworkOtherThan(keep)
        deleteLessonsOtherThan(keep)
        deletePeriodsOtherThan(keep)
        deleteWeeksOtherThan(keep)
        deleteStudentsOtherThan(keep)
        val before = students().associateBy { it.id }
        insertStudents(
            rows.map { row ->
                val old = before[row.id] ?: return@map row
                row.copy(
                    periodsSyncedAt = old.periodsSyncedAt,
                    marksFrom = old.marksFrom,
                    marksTo = old.marksTo,
                    marksSyncedAt = old.marksSyncedAt,
                )
            },
        )
    }

    /**
     * One pupil's lessons for whole weeks [monday] .. [sunday], replacing what
     * was there and stamping every week in the span as fetched — including the
     * ones the answer had nothing for, which is what makes an empty week
     * «пусто» rather than «не загружено».
     *
     * A row dated outside the span is dropped: it would sit in a week nobody
     * stamped, and be drawn under «не загружено» as if it were the whole week.
     */
    @Transaction
    open suspend fun replaceLessons(
        studentId: Long,
        monday: LocalDate,
        sunday: LocalDate,
        rows: List<DiaryLessonEntity>,
        at: Long,
    ) {
        deleteLessons(studentId, monday, sunday)
        insertLessons(rows.filter { it.studentId == studentId && it.date in monday..sunday })
        val mondays = mondaysOf(monday, sunday)
        insertWeeksIfAbsent(mondays.map { DiaryWeekEntity(studentId = studentId, monday = it) })
        stampLessons(studentId, mondays, at)
    }

    /** @see replaceLessons */
    @Transaction
    open suspend fun replaceHomework(
        studentId: Long,
        monday: LocalDate,
        sunday: LocalDate,
        rows: List<DiaryHomeworkEntity>,
        at: Long,
    ) {
        deleteHomework(studentId, monday, sunday)
        insertHomework(rows.filter { it.studentId == studentId && it.dueDate in monday..sunday })
        val mondays = mondaysOf(monday, sunday)
        insertWeeksIfAbsent(mondays.map { DiaryWeekEntity(studentId = studentId, monday = it) })
        stampHomework(studentId, mondays, at)
    }

    /**
     * One pupil's marks for [from] .. [to]. One window per pupil, the latest:
     * the marks tab asks for one window at a time, and keeping several would
     * only be a question of which to draw.
     */
    @Transaction
    open suspend fun replaceMarks(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        rows: List<DiaryMarkEntity>,
        at: Long,
    ) {
        deleteMarks(studentId)
        insertMarks(rows.filter { it.studentId == studentId })
        stampMarks(studentId, from, to, at)
    }

    @Transaction
    open suspend fun replacePeriods(studentId: Long, rows: List<DiaryPeriodEntity>, at: Long) {
        deletePeriods(studentId)
        insertPeriods(rows.filter { it.studentId == studentId })
        stampPeriods(studentId, at)
    }

    /** Everything, table by table. Signing out, or another account signing in. */
    @Transaction
    open suspend fun clear() {
        deleteAllMarks()
        deleteAllHomework()
        deleteAllLessons()
        deleteAllPeriods()
        deleteAllWeeks()
        deleteAllStudents()
    }

    private fun mondaysOf(monday: LocalDate, sunday: LocalDate): List<LocalDate> =
        generateSequence(monday) { it.plusWeeks(1) }.takeWhile { !it.isAfter(sunday) }.toList()
}

/** What [DiaryDao.weekSnapshot] reads. */
internal data class DiaryWeekSnapshot(
    val week: DiaryWeekEntity?,
    val lessons: List<DiaryLessonEntity>,
    val homework: List<DiaryHomeworkEntity>,
)

/** What [DiaryDao.marksSnapshot] reads. */
internal data class DiaryMarksSnapshot(
    val student: DiaryStudentEntity?,
    val marks: List<DiaryMarkEntity>,
)

/** What [DiaryDao.periodsSnapshot] reads. */
internal data class DiaryPeriodsSnapshot(
    val student: DiaryStudentEntity?,
    val periods: List<DiaryPeriodEntity>,
)

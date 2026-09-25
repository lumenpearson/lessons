package com.lumenpearson.lessons.core.data.diary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

/**
 * What `diary.db` holds: the diary as this phone last read it, for one account.
 *
 * Every row is a copy of an answer our server gave, and nothing here is
 * authored on the phone — corrections live on the server and arrive already
 * laid over, with `edits` saying which fields. That is why the database may be
 * dropped whole on a schema change or a sign-out.
 *
 * There are no foreign keys. Every delete in [DiaryDao] names the pupil it is
 * about, so nothing depends on `PRAGMA foreign_keys` (the rule
 * `DaoDeletePolicyTest` holds for the timetable, and `DiaryDaoPolicyTest` for
 * this file).
 *
 * The timestamps are epoch milliseconds of the moment the answer arrived. A
 * `null` stamp is «never fetched», which is a different thing from «fetched and
 * empty» — the same distinction `synced_window` exists for in the timetable.
 */

/**
 * One pupil the account may see, and the stamps of what was fetched per pupil
 * rather than per week.
 *
 * @property marksFrom the window the cached marks cover, inclusive; `null`
 *   until marks were fetched once. One window per pupil, the last one read.
 */
@Entity(tableName = "diary_student")
internal data class DiaryStudentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "first_name") val firstName: String,
    @ColumnInfo(name = "last_name") val lastName: String,
    @ColumnInfo(name = "middle_name") val middleName: String?,
    @ColumnInfo(name = "full_name") val fullName: String,
    @ColumnInfo(name = "school") val school: String?,
    @ColumnInfo(name = "class_name") val className: String?,
    /** The order the server listed the pupils in, which is the picker's. */
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
    @ColumnInfo(name = "periods_synced_at") val periodsSyncedAt: Long? = null,
    @ColumnInfo(name = "marks_from") val marksFrom: LocalDate? = null,
    @ColumnInfo(name = "marks_to") val marksTo: LocalDate? = null,
    @ColumnInfo(name = "marks_synced_at") val marksSyncedAt: Long? = null,
)

/**
 * One Monday-to-Sunday week of one pupil, and whether each half of it was
 * fetched. A week with a stamp and no rows is a week the diary says is empty.
 */
@Entity(
    tableName = "diary_week",
    primaryKeys = ["student_id", "monday"],
)
internal data class DiaryWeekEntity(
    @ColumnInfo(name = "student_id") val studentId: Long,
    @ColumnInfo(name = "monday") val monday: LocalDate,
    @ColumnInfo(name = "lessons_synced_at") val lessonsSyncedAt: Long? = null,
    @ColumnInfo(name = "homework_synced_at") val homeworkSyncedAt: Long? = null,
)

/** A term, so the marks window can be worked out offline the way the screen works it out. */
@Entity(
    tableName = "diary_period",
    primaryKeys = ["student_id", "id"],
)
internal data class DiaryPeriodEntity(
    @ColumnInfo(name = "student_id") val studentId: Long,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "starts_on") val startsOn: LocalDate?,
    @ColumnInfo(name = "ends_on") val endsOn: LocalDate?,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
    @ColumnInfo(name = "position") val position: Int,
)

/**
 * One lesson. [edits] is the corrections as JSON (`DiaryRowCodec`): they are
 * only ever read back with their row, so a table of their own would buy a join
 * for nothing.
 */
@Entity(
    tableName = "diary_lesson",
    indices = [Index(value = ["student_id", "date"])],
)
internal data class DiaryLessonEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "student_id") val studentId: Long,
    @ColumnInfo(name = "date") val date: LocalDate,
    @ColumnInfo(name = "number") val number: Int?,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "starts_at") val startsAt: LocalTime?,
    @ColumnInfo(name = "ends_at") val endsAt: LocalTime?,
    @ColumnInfo(name = "room") val room: String?,
    @ColumnInfo(name = "teacher") val teacher: String?,
    @ColumnInfo(name = "homework") val homework: String?,
    @ColumnInfo(name = "topic") val topic: String?,
    @ColumnInfo(name = "target") val target: String,
    @ColumnInfo(name = "edits") val edits: String,
    @ColumnInfo(name = "ambiguous") val ambiguous: Boolean,
    /** The order the server sent them in, which is the day's order. */
    @ColumnInfo(name = "position") val position: Int,
)

@Entity(
    tableName = "diary_homework",
    indices = [Index(value = ["student_id", "due_date"])],
)
internal data class DiaryHomeworkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "student_id") val studentId: Long,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
    @ColumnInfo(name = "upstream_id") val upstreamId: Long?,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "teacher") val teacher: String?,
    @ColumnInfo(name = "target") val target: String,
    @ColumnInfo(name = "edits") val edits: String,
    @ColumnInfo(name = "ambiguous") val ambiguous: Boolean,
    @ColumnInfo(name = "position") val position: Int,
)

/** One register entry. [kind] is `DiaryMarkKind.name`, read back tolerantly. */
@Entity(
    tableName = "diary_mark",
    indices = [Index(value = ["student_id"])],
)
internal data class DiaryMarkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "student_id") val studentId: Long,
    @ColumnInfo(name = "upstream_id") val upstreamId: Long?,
    @ColumnInfo(name = "subject_id") val subjectId: Long?,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "date") val date: LocalDate?,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "comment") val comment: String?,
    @ColumnInfo(name = "position") val position: Int,
)

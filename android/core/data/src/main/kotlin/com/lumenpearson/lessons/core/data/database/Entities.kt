package com.lumenpearson.lessons.core.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

/**
 * The cache schema. Room is the single source of truth: the UI only ever reads
 * these tables, and the network's only job is to replace their contents.
 *
 * Column names are snake_case to match the wire format, which makes the exported
 * schema JSON readable next to `server/app/schemas.py` during a migration.
 */

/**
 * The joined class. At most one row exists - the app belongs to one class at a
 * time - and its presence is what "has ever synced" means: no row, no timetable.
 */
@Entity(tableName = "school_class")
internal data class SchoolClassEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "school") val school: String?,
    @ColumnInfo(name = "time_zone_id") val timeZoneId: String,
    /** Server's `generated_at`, or arrival time; drives the "synced N ago" label. */
    @ColumnInfo(name = "synced_at_epoch_millis") val syncedAtEpochMillis: Long,
)

/**
 * One calendar date.
 *
 * The primary key is a surrogate id rather than the date because the bundle can
 * legitimately contain the same date twice: once inside the synced window and
 * once as `next_school_day`, which the server resolves past the window's end.
 * [isNextSchoolDay] separates the two, and the unique index makes the pair the
 * real key.
 */
@Entity(
    tableName = "school_day",
    indices = [
        Index(value = ["date"]),
        Index(value = ["date", "is_next_school_day"], unique = true),
    ],
)
internal data class SchoolDayEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "date") val date: LocalDate,
    @ColumnInfo(name = "weekday") val weekday: Int,
    /** `DayKind` name; stored as text so an unknown future kind survives a downgrade. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "note") val note: String?,
    /** True for the lookahead day that lives outside the synced window. */
    @ColumnInfo(name = "is_next_school_day") val isNextSchoolDay: Boolean = false,
)

/**
 * A lesson, owned by its day.
 *
 * `index` is a reserved word in SQL, hence the `lesson_index` column name; the
 * Kotlin property keeps the domain name.
 */
@Entity(
    tableName = "lesson",
    foreignKeys = [
        ForeignKey(
            entity = SchoolDayEntity::class,
            parentColumns = ["id"],
            childColumns = ["day_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["day_id"])],
)
internal data class LessonEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "day_id") val dayId: Long,
    @ColumnInfo(name = "lesson_index") val index: Int,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "starts_at") val startsAt: LocalTime,
    @ColumnInfo(name = "ends_at") val endsAt: LocalTime,
    @ColumnInfo(name = "room") val room: String?,
    @ColumnInfo(name = "teacher") val teacher: String?,
    @ColumnInfo(name = "color_hex") val colorHex: String?,
    @ColumnInfo(name = "is_replaced") val isReplaced: Boolean,
    @ColumnInfo(name = "is_cancelled") val isCancelled: Boolean,
    @ColumnInfo(name = "note") val note: String?,
)

/** Anything on a day's timeline that is not a lesson. */
@Entity(
    tableName = "event",
    foreignKeys = [
        ForeignKey(
            entity = SchoolDayEntity::class,
            parentColumns = ["id"],
            childColumns = ["day_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["day_id"])],
)
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "day_id") val dayId: Long,
    @ColumnInfo(name = "title") val title: String,
    /** `EventKind` name; text for the same forward-compatibility reason as day kind. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "starts_at") val startsAt: LocalTime,
    @ColumnInfo(name = "ends_at") val endsAt: LocalTime,
    @ColumnInfo(name = "location") val location: String?,
    @ColumnInfo(name = "covers_lesson") val coversLesson: Boolean,
)

/** Homework set for a day. Ordering is server order; there is no natural key. */
@Entity(
    tableName = "homework",
    foreignKeys = [
        ForeignKey(
            entity = SchoolDayEntity::class,
            parentColumns = ["id"],
            childColumns = ["day_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["day_id"])],
)
internal data class HomeworkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "day_id") val dayId: Long,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "attachment_url") val attachmentUrl: String?,
)

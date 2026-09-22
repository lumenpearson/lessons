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
 * A joined class. One row per membership: the device may belong to several
 * classes at once and shows one of them at a time, so the row that matters is
 * the one whose [id] the stored session names. Its presence is what "has ever
 * synced" means for that class - no row, no timetable, and switching to a class
 * that has none lands on an empty screen until the first sync fills it.
 */
@Entity(tableName = "school_class")
internal data class SchoolClassEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "grade") val grade: Int? = null,
    @ColumnInfo(name = "letter") val letter: String? = null,
    @ColumnInfo(name = "school") val school: String?,
    @ColumnInfo(name = "time_zone_id") val timeZoneId: String,
    @ColumnInfo(name = "term_kind") val termKind: String? = null,
    /**
     * The terms, as `index|kind|start|end` lines.
     *
     * One column rather than a table of its own: there are two to four of
     * them, they only ever travel with the class, and a sync replaces all of
     * them at once — a table would buy a join and a delete for nothing. The
     * cache is disposable anyway (`fallbackToDestructiveMigration`), so the
     * format is free to change with the schema version.
     */
    @ColumnInfo(name = "terms") val terms: String = "",
    /** Server's `generated_at`, or arrival time; drives the "synced N ago" label. */
    @ColumnInfo(name = "synced_at_epoch_millis") val syncedAtEpochMillis: Long,
)

/**
 * One school year this device has actually fetched, for one class.
 *
 * The cache used to hold exactly one window per class, so «is this date in the
 * cache» was the same question as «is there a class row», and every date past
 * the current year read «Нет данных» — which on screen is indistinguishable
 * from «no lessons that day». Holding several years means that question has to
 * be asked of a range rather than of the class, and a row here is the answer.
 *
 * It is a table rather than something derived from the days present, because
 * **a year that was fetched and is genuinely empty has to be tellable from one
 * that was never fetched.** A class made in March has no days at all before it;
 * counting rows would report its first September as «not loaded» for ever, and
 * the calendar would sit on a spinner that never stops.
 *
 * Keyed by the year's opening calendar year — see `SchoolYear.openingYearOf` —
 * because one integer cannot disagree with itself the way a pair of dates can,
 * and the same number keys the row, the `ETag` signature and the request.
 *
 * @property syncedAtEpochMillis when this window was last filled, as the phone
 *   reckoned it. **Nothing reads it.** It decided eviction once, and that rule
 *   was wrong twice over — see `TimetableRepositoryImpl.prune`, which sorts by
 *   distance from the year holding today and says why at length. The sentence
 *   that used to be here named this column as the rule, which left two
 *   documents in one repository giving opposite answers to one question; the
 *   column outlived the rule because dropping it is a schema version bump, and
 *   this database is built with `fallbackToDestructiveMigration`, so it would
 *   empty every installed phone's cache to save eight bytes in at most three
 *   rows per class.
 */
@Entity(
    tableName = "synced_window",
    primaryKeys = ["class_id", "opening_year"],
    indices = [Index(value = ["class_id"])],
)
internal data class SyncedWindowEntity(
    @ColumnInfo(name = "class_id") val classId: Long,
    @ColumnInfo(name = "opening_year") val openingYear: Int,
    @ColumnInfo(name = "starts_on") val startsOn: LocalDate,
    @ColumnInfo(name = "ends_on") val endsOn: LocalDate,
    @ColumnInfo(name = "synced_at_epoch_millis") val syncedAtEpochMillis: Long,
)

/**
 * One calendar date, in one class.
 *
 * The primary key is a surrogate id rather than the date because the bundle can
 * legitimately contain the same date twice: once inside the synced window and
 * once as `next_school_day`, which the server resolves past the window's end.
 * [isNextSchoolDay] separates the two, and the unique index makes the triple
 * the real key.
 *
 * [classId] is on the day rather than only on the class row because it is what
 * every read filters by: two classes cached side by side have a Monday each,
 * and a query that forgot the filter would draw one class's lessons under the
 * other's name. Lessons, events and homework need no such column - they reach
 * their class through `day_id`, and deleting the day takes them with it.
 */
@Entity(
    tableName = "school_day",
    indices = [
        Index(value = ["class_id", "date"]),
        Index(value = ["class_id", "date", "is_next_school_day"], unique = true),
    ],
)
internal data class SchoolDayEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "class_id") val classId: Long,
    @ColumnInfo(name = "date") val date: LocalDate,
    @ColumnInfo(name = "weekday") val weekday: Int,
    /** `DayKind` name; stored as text so an unknown future kind survives a downgrade. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "note") val note: String?,
    /** True for the lookahead day that lives outside the synced window. */
    @ColumnInfo(name = "is_next_school_day") val isNextSchoolDay: Boolean = false,
    /**
     * `DayOffReason` name, or null. Text for the same reason [kind] is: a
     * reason this build has never heard of comes back as null rather than
     * taking the row with it.
     */
    @ColumnInfo(name = "off_reason") val offReason: String? = null,
    /** The named date's stable code, and its Russian title as the server sent it. */
    @ColumnInfo(name = "holiday_code") val holidayCode: String? = null,
    @ColumnInfo(name = "holiday_title") val holidayTitle: String? = null,
    @ColumnInfo(name = "holiday_stops_lessons") val holidayStopsLessons: Boolean = false,
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

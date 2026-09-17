package com.lumenpearson.lessons.core.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Every read and write of the cached timetable.
 *
 * One DAO instead of four: the tables are only ever touched together - a sync
 * replaces all of them, a screen reads all of them - so splitting them would buy
 * nothing but extra plumbing.
 *
 * **Every read takes a class id, and none of them defaults it.** The cache holds
 * one window per joined class side by side, so an unfiltered `SELECT` returns
 * two classes' Mondays and the screen draws whichever came first. A required
 * parameter is the cheapest way to make forgetting the filter a compile error
 * rather than a wrong timetable that looks right.
 *
 * It is an abstract class rather than an interface because [replaceAll],
 * [clear] and [clearAll] need bodies wrapped in `@Transaction`, which Room
 * implements by overriding a concrete method.
 */
@Dao
internal abstract class TimetableDao {

    /** Emits `null` until the first successful sync writes this class's row. */
    @Query("SELECT * FROM school_class WHERE id = :classId")
    abstract fun observeSchoolClass(classId: Long): Flow<SchoolClassEntity?>

    /** The synced window, chronological. Epoch-day storage makes this a plain integer sort. */
    @Transaction
    @Query(
        "SELECT * FROM school_day WHERE class_id = :classId AND is_next_school_day = 0 " +
            "ORDER BY date ASC",
    )
    abstract fun observeDays(classId: Long): Flow<List<SchoolDayWithDetails>>

    /** The lookahead day beyond the window, if the server resolved one. */
    @Transaction
    @Query(
        "SELECT * FROM school_day WHERE class_id = :classId AND is_next_school_day = 1 " +
            "ORDER BY date ASC LIMIT 1",
    )
    abstract fun observeNextSchoolDay(classId: Long): Flow<SchoolDayWithDetails?>

    /**
     * One-shot twins of the observers, for the widget and the sync worker, which
     * want one value and no subscription.
     */
    @Query("SELECT * FROM school_class WHERE id = :classId")
    abstract suspend fun schoolClass(classId: Long): SchoolClassEntity?

    @Transaction
    @Query(
        "SELECT * FROM school_day WHERE class_id = :classId AND is_next_school_day = 0 " +
            "ORDER BY date ASC",
    )
    abstract suspend fun days(classId: Long): List<SchoolDayWithDetails>

    @Transaction
    @Query(
        "SELECT * FROM school_day WHERE class_id = :classId AND is_next_school_day = 1 " +
            "ORDER BY date ASC LIMIT 1",
    )
    abstract suspend fun nextSchoolDay(classId: Long): SchoolDayWithDetails?

    /**
     * All three reads a render needs, taken inside one transaction.
     *
     * [replaceAll] swaps the class row and the days atomically, so reading them
     * through three separate statements can straddle that swap and return the
     * class from before it with the days from after — an old "обновлено в …"
     * над a new week, or the reverse. The widget redraws on the sync broadcast,
     * which puts it at exactly that instant.
     */
    @Transaction
    open suspend fun snapshot(classId: Long): TimetableSnapshot? {
        val schoolClass = schoolClass(classId) ?: return null
        return TimetableSnapshot(
            schoolClass = schoolClass,
            days = days(classId),
            nextSchoolDay = nextSchoolDay(classId),
        )
    }

    /**
     * Building blocks of [replaceAll]. They are public only because Room has to
     * generate overrides; call [replaceAll] instead, so the cache is never left
     * half-written.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSchoolClass(entity: SchoolClassEntity)

    /** @return the generated row id, which the day's children are stamped with. */
    @Insert
    abstract suspend fun insertDay(entity: SchoolDayEntity): Long

    @Insert
    abstract suspend fun insertLessons(entities: List<LessonEntity>)

    @Insert
    abstract suspend fun insertEvents(entities: List<EventEntity>)

    @Insert
    abstract suspend fun insertHomework(entities: List<HomeworkEntity>)

    /**
     * Per-class deletes. The children are reached through their day rather than
     * carrying a class of their own, which is why each of these is a subquery
     * instead of a flat `WHERE class_id = …`.
     */
    @Query(
        "DELETE FROM homework WHERE day_id IN " +
            "(SELECT id FROM school_day WHERE class_id = :classId)",
    )
    abstract suspend fun deleteHomeworkOf(classId: Long)

    @Query(
        "DELETE FROM event WHERE day_id IN " +
            "(SELECT id FROM school_day WHERE class_id = :classId)",
    )
    abstract suspend fun deleteEventsOf(classId: Long)

    @Query(
        "DELETE FROM lesson WHERE day_id IN " +
            "(SELECT id FROM school_day WHERE class_id = :classId)",
    )
    abstract suspend fun deleteLessonsOf(classId: Long)

    @Query("DELETE FROM school_day WHERE class_id = :classId")
    abstract suspend fun deleteDaysOf(classId: Long)

    /**
     * Move the «обновлено N назад» mark without touching a single row of data.
     *
     * For the sync that got a `304`: the window is byte-for-byte what is
     * cached, so there is nothing to write — but the check did happen, and a
     * mark left alone keeps growing on a phone that is syncing perfectly.
     */
    @Query("UPDATE school_class SET synced_at_epoch_millis = :millis WHERE id = :classId")
    abstract suspend fun touchSyncedAt(classId: Long, millis: Long)

    @Query("DELETE FROM school_class WHERE id = :classId")
    abstract suspend fun deleteSchoolClass(classId: Long)

    @Query("DELETE FROM homework")
    abstract suspend fun deleteAllHomework()

    @Query("DELETE FROM event")
    abstract suspend fun deleteAllEvents()

    @Query("DELETE FROM lesson")
    abstract suspend fun deleteAllLessons()

    @Query("DELETE FROM school_day")
    abstract suspend fun deleteAllDays()

    @Query("DELETE FROM school_class")
    abstract suspend fun deleteAllSchoolClasses()

    /**
     * Swaps one class's whole synced window atomically.
     *
     * Wipe-and-reinsert rather than upsert: the server owns the schedule
     * completely, and a lesson that was deleted upstream has no key the client
     * could use to notice its absence. Doing it in one transaction means readers
     * either see the old window or the new one, never a half-empty week - which
     * matters because the widget can wake up mid-sync.
     *
     * Only this class's rows are touched. A sync of 7«А» that wiped the table
     * would empty 9«Б» behind the user's back, and they would find out by
     * switching to it and seeing nothing.
     *
     * @param nextSchoolDay the lookahead day, already flagged; pass `null` when
     * the server could not resolve one (long holiday at the end of the year).
     */
    @Transaction
    open suspend fun replaceAll(
        schoolClass: SchoolClassEntity,
        days: List<SchoolDayRecord>,
        nextSchoolDay: SchoolDayRecord?,
    ) {
        clear(schoolClass.id)
        insertSchoolClass(schoolClass)
        (days + listOfNotNull(nextSchoolDay)).forEach { record ->
            // Re-stamped rather than trusted: the class row being written is
            // the one authority on whose window this is, so a record built for
            // another class cannot be filed under this one's id.
            val dayId = insertDay(record.day.copy(classId = schoolClass.id))
            insertLessons(record.lessons.map { it.copy(dayId = dayId) })
            insertEvents(record.events.map { it.copy(dayId = dayId) })
            insertHomework(record.homework.map { it.copy(dayId = dayId) })
        }
    }

    /**
     * Empties one class's cache. Used when that class is left: a device that is
     * no longer in it must not keep showing its timetable, and the other classes
     * on the phone have nothing to do with the one being left.
     *
     * Children are deleted explicitly even though the foreign keys cascade, so
     * the wipe does not silently depend on `PRAGMA foreign_keys` being on.
     */
    @Transaction
    open suspend fun clear(classId: Long) {
        deleteHomeworkOf(classId)
        deleteEventsOf(classId)
        deleteLessonsOf(classId)
        deleteDaysOf(classId)
        deleteSchoolClass(classId)
    }

    @Query(
        "DELETE FROM homework WHERE day_id IN " +
            "(SELECT id FROM school_day WHERE class_id NOT IN (:keep))",
    )
    abstract suspend fun deleteHomeworkOutside(keep: Collection<Long>)

    @Query(
        "DELETE FROM event WHERE day_id IN " +
            "(SELECT id FROM school_day WHERE class_id NOT IN (:keep))",
    )
    abstract suspend fun deleteEventsOutside(keep: Collection<Long>)

    @Query(
        "DELETE FROM lesson WHERE day_id IN " +
            "(SELECT id FROM school_day WHERE class_id NOT IN (:keep))",
    )
    abstract suspend fun deleteLessonsOutside(keep: Collection<Long>)

    @Query("DELETE FROM school_day WHERE class_id NOT IN (:keep)")
    abstract suspend fun deleteDaysOutside(keep: Collection<Long>)

    @Query("DELETE FROM school_class WHERE id NOT IN (:keep)")
    abstract suspend fun deleteSchoolClassesOutside(keep: Collection<Long>)

    /**
     * Drops every class the device is no longer in.
     *
     * A sweep rather than a delete at the moment of leaving, because the case
     * it exists for cannot be caught there: a sync already in flight when a
     * class is left lands afterwards and `replaceAll` re-creates the whole
     * window for a class nobody can see any more. Every read is class-filtered
     * so none of it is ever drawn — it is a year of somebody's timetable kept
     * on a phone that asked to be rid of it, which is the part that matters.
     *
     * An empty [keep] is the whole cache, spelled as such: `NOT IN ()` is not
     * valid SQL.
     */
    @Transaction
    open suspend fun retainOnly(keep: Collection<Long>) {
        if (keep.isEmpty()) {
            clearAll()
            return
        }
        deleteHomeworkOutside(keep)
        deleteEventsOutside(keep)
        deleteLessonsOutside(keep)
        deleteDaysOutside(keep)
        deleteSchoolClassesOutside(keep)
    }

    /** Empties the whole cache, every class at once. Used on full sign-out. */
    @Transaction
    open suspend fun clearAll() {
        deleteAllHomework()
        deleteAllEvents()
        deleteAllLessons()
        deleteAllDays()
        deleteAllSchoolClasses()
    }
}

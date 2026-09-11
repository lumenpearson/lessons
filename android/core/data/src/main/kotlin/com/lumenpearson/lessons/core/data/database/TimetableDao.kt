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
 * It is an abstract class rather than an interface because [replaceAll] and
 * [clearAll] need bodies wrapped in `@Transaction`, which Room implements by
 * overriding a concrete method.
 */
@Dao
internal abstract class TimetableDao {

    /** Emits `null` until the first successful sync writes the class row. */
    @Query("SELECT * FROM school_class LIMIT 1")
    abstract fun observeSchoolClass(): Flow<SchoolClassEntity?>

    /** The synced window, chronological. Epoch-day storage makes this a plain integer sort. */
    @Transaction
    @Query("SELECT * FROM school_day WHERE is_next_school_day = 0 ORDER BY date ASC")
    abstract fun observeDays(): Flow<List<SchoolDayWithDetails>>

    /** The lookahead day beyond the window, if the server resolved one. */
    @Transaction
    @Query("SELECT * FROM school_day WHERE is_next_school_day = 1 ORDER BY date ASC LIMIT 1")
    abstract fun observeNextSchoolDay(): Flow<SchoolDayWithDetails?>

    /**
     * One-shot twins of the observers, for the widget and the sync worker, which
     * want one value and no subscription.
     */
    @Query("SELECT * FROM school_class LIMIT 1")
    abstract suspend fun schoolClass(): SchoolClassEntity?

    @Transaction
    @Query("SELECT * FROM school_day WHERE is_next_school_day = 0 ORDER BY date ASC")
    abstract suspend fun days(): List<SchoolDayWithDetails>

    @Transaction
    @Query("SELECT * FROM school_day WHERE is_next_school_day = 1 ORDER BY date ASC LIMIT 1")
    abstract suspend fun nextSchoolDay(): SchoolDayWithDetails?

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
    open suspend fun snapshot(): TimetableSnapshot? {
        val schoolClass = schoolClass() ?: return null
        return TimetableSnapshot(
            schoolClass = schoolClass,
            days = days(),
            nextSchoolDay = nextSchoolDay(),
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

    @Query("DELETE FROM homework")
    abstract suspend fun deleteAllHomework()

    @Query("DELETE FROM event")
    abstract suspend fun deleteAllEvents()

    @Query("DELETE FROM lesson")
    abstract suspend fun deleteAllLessons()

    @Query("DELETE FROM school_day")
    abstract suspend fun deleteAllDays()

    @Query("DELETE FROM school_class")
    abstract suspend fun deleteSchoolClass()

    /**
     * Swaps the whole synced window atomically.
     *
     * Wipe-and-reinsert rather than upsert: the server owns the schedule
     * completely, and a lesson that was deleted upstream has no key the client
     * could use to notice its absence. Doing it in one transaction means readers
     * either see the old window or the new one, never a half-empty week - which
     * matters because the widget can wake up mid-sync.
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
        clearAll()
        insertSchoolClass(schoolClass)
        (days + listOfNotNull(nextSchoolDay)).forEach { record ->
            val dayId = insertDay(record.day)
            insertLessons(record.lessons.map { it.copy(dayId = dayId) })
            insertEvents(record.events.map { it.copy(dayId = dayId) })
            insertHomework(record.homework.map { it.copy(dayId = dayId) })
        }
    }

    /**
     * Empties the cache. Used on sign-out: a device that left the class must not
     * keep showing its timetable.
     *
     * Children are deleted explicitly even though the foreign keys cascade, so
     * the wipe does not silently depend on `PRAGMA foreign_keys` being on.
     */
    @Transaction
    open suspend fun clearAll() {
        deleteAllHomework()
        deleteAllEvents()
        deleteAllLessons()
        deleteAllDays()
        deleteSchoolClass()
    }
}

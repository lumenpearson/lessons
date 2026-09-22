package com.lumenpearson.lessons.core.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.time.LocalDate
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
 * It is an abstract class rather than an interface because [replaceWindow],
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
     * [replaceWindow] swaps the class row and a year's days atomically, so reading them
     * through three separate statements can straddle that swap and return the
     * class from before it with the days from after — an old "обновлено в …"
     * over a new week, or the reverse. The widget redraws on the sync broadcast,
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
     * The days inside a date range, for a reader that needs a fortnight rather
     * than a year. Inclusive at both ends; the `class_id, date` index makes it
     * a range scan over epoch days.
     */
    @Transaction
    @Query(
        "SELECT * FROM school_day WHERE class_id = :classId AND is_next_school_day = 0 " +
            "AND date BETWEEN :from AND :to ORDER BY date ASC",
    )
    abstract suspend fun daysBetween(
        classId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<SchoolDayWithDetails>

    /**
     * The first cached day after [after] that actually teaches something.
     *
     * This is the SQL half of `Timetable.schoolDayAfter`, and the two have to
     * keep agreeing: a bounded read hands its answer over as the timetable's
     * `nextSchoolDay`, which is where that function looks when its own `days`
     * run out. So the `EXISTS` clause spells out what `SchoolDay.hasLessons`
     * means — at least one lesson that is **not** cancelled — because a day
     * whose whole timetable was struck out is not a school day to point at,
     * and asking «are there rows» instead would point at it.
     *
     * It is what makes a bounded read able to answer about the far side of a
     * gap: the first of September seen from July, or the Monday back seen from
     * the middle of the winter holidays. Without it a reader that asked for a
     * fortnight would report «no school day ahead» on the two occasions in the
     * year anybody wants that answer.
     */
    @Transaction
    @Query(
        "SELECT * FROM school_day WHERE class_id = :classId AND is_next_school_day = 0 " +
            "AND date > :after AND EXISTS (" +
            "SELECT 1 FROM lesson WHERE lesson.day_id = school_day.id AND lesson.is_cancelled = 0" +
            ") ORDER BY date ASC LIMIT 1",
    )
    abstract suspend fun firstTeachingDayAfter(
        classId: Long,
        after: LocalDate,
    ): SchoolDayWithDetails?

    /**
     * [snapshot], bounded to a date range.
     *
     * One transaction for the same reason [snapshot] is one: `replaceWindow` swaps
     * the class row and the days together, and three statements either side of
     * that swap return a new week under an old «обновлено в …».
     *
     * The lookahead is resolved twice over, and the order is the point. The
     * first day with lessons after [to] is looked for in the cache first, so a
     * reader bounded to a fortnight still knows what the year holds a week
     * after it; the stored lookahead row is the fallback, for the end of the
     * cached window, where by construction there is nothing after it to find.
     * That keeps `schoolDayAfter` answering the same thing it answers from the
     * whole year, for every date inside the bound.
     */
    @Transaction
    open suspend fun snapshotBetween(
        classId: Long,
        from: LocalDate,
        to: LocalDate,
    ): TimetableSnapshot? {
        val schoolClass = schoolClass(classId) ?: return null
        return TimetableSnapshot(
            schoolClass = schoolClass,
            days = daysBetween(classId, from, to),
            nextSchoolDay = firstTeachingDayAfter(classId, to) ?: nextSchoolDay(classId),
        )
    }

    /**
     * Building blocks of [replaceWindow]. They are public only because Room has to
     * generate overrides; call [replaceWindow] instead, so the cache is never left
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
     *
     * Answers with the number of rows it moved, which is how the caller finds
     * out there is no cache to mark. That is a real state: the ETag lives in
     * the preferences and outlives every wipe of this table, so a leave and a
     * re-join of the same class sends a tag the server still matches while
     * the phone holds nothing at all.
     */
    @Query("UPDATE school_class SET synced_at_epoch_millis = :millis WHERE id = :classId")
    abstract suspend fun touchSyncedAt(classId: Long, millis: Long): Int

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

    /** Every school year this class has actually fetched, newest first. */
    @Query("SELECT * FROM synced_window WHERE class_id = :classId ORDER BY opening_year DESC")
    abstract fun observeWindows(classId: Long): Flow<List<SyncedWindowEntity>>

    /** @see observeWindows */
    @Query("SELECT * FROM synced_window WHERE class_id = :classId ORDER BY opening_year DESC")
    abstract suspend fun windows(classId: Long): List<SyncedWindowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertWindow(entity: SyncedWindowEntity)

    @Query("DELETE FROM synced_window WHERE class_id = :classId AND opening_year = :openingYear")
    abstract suspend fun deleteWindow(classId: Long, openingYear: Int)

    @Query("DELETE FROM synced_window WHERE class_id = :classId")
    abstract suspend fun deleteWindowsOf(classId: Long)

    @Query("DELETE FROM synced_window WHERE class_id NOT IN (:keep)")
    abstract suspend fun deleteWindowsOutside(keep: Collection<Long>)

    @Query("DELETE FROM synced_window")
    abstract suspend fun deleteAllWindows()

    /**
     * Ranged deletes, for replacing one window and leaving the others standing.
     *
     * The lookahead row is excluded from every one of them. It is the day
     * *past* the window it was resolved for, so a date inside it belongs to
     * whichever window it falls in as well, and a delete that swept it up while
     * replacing a neighbouring year would take away a row that year has nothing
     * to do with. [replaceWindow] rewrites it explicitly.
     *
     * It is **not** what answers `schoolDayAfter` across a gap any more, and
     * this is where that used to be written down. [firstTeachingDayAfter] is,
     * over a cache that is now a whole school year: the server resolves the
     * lookahead at most three weeks past the last lesson *inside the window it
     * was asked for*, and this client asks for 1 September to 31 May, so those
     * three weeks land in June, which is out of season for every class — a term
     * cannot be typed past the year's end. `next_school_day` therefore comes
     * back `null` on every bundle this app asks for, and the exclusion below
     * guards a row nothing writes today. See the note on [replaceWindow]'s
     * `nextSchoolDay` for why the path is kept standing all the same.
     */
    @Query(
        "DELETE FROM homework WHERE day_id IN (SELECT id FROM school_day " +
            "WHERE class_id = :classId AND is_next_school_day = 0 " +
            "AND date BETWEEN :from AND :to)",
    )
    abstract suspend fun deleteHomeworkBetween(classId: Long, from: LocalDate, to: LocalDate)

    @Query(
        "DELETE FROM event WHERE day_id IN (SELECT id FROM school_day " +
            "WHERE class_id = :classId AND is_next_school_day = 0 " +
            "AND date BETWEEN :from AND :to)",
    )
    abstract suspend fun deleteEventsBetween(classId: Long, from: LocalDate, to: LocalDate)

    @Query(
        "DELETE FROM lesson WHERE day_id IN (SELECT id FROM school_day " +
            "WHERE class_id = :classId AND is_next_school_day = 0 " +
            "AND date BETWEEN :from AND :to)",
    )
    abstract suspend fun deleteLessonsBetween(classId: Long, from: LocalDate, to: LocalDate)

    @Query(
        "DELETE FROM school_day WHERE class_id = :classId AND is_next_school_day = 0 " +
            "AND date BETWEEN :from AND :to",
    )
    abstract suspend fun deleteDaysBetween(classId: Long, from: LocalDate, to: LocalDate)

    /**
     * The stored lookahead row, which belongs to no window in particular.
     *
     * Its children go explicitly, like everywhere else in this file: no delete
     * here may depend on `PRAGMA foreign_keys` being on, and this was the one
     * that did. The row it removes is also the one row no ranged delete
     * reaches, so lessons orphaned here would be unreachable by every read and
     * by every later wipe — a leak with no query that could ever find it.
     */
    @Transaction
    open suspend fun deleteLookaheadOf(classId: Long) {
        deleteLookaheadHomeworkOf(classId)
        deleteLookaheadEventsOf(classId)
        deleteLookaheadLessonsOf(classId)
        deleteLookaheadDaysOf(classId)
    }

    /** Building blocks of [deleteLookaheadOf]; call that instead. */
    @Query(
        "DELETE FROM homework WHERE day_id IN (SELECT id FROM school_day " +
            "WHERE class_id = :classId AND is_next_school_day = 1)",
    )
    abstract suspend fun deleteLookaheadHomeworkOf(classId: Long)

    @Query(
        "DELETE FROM event WHERE day_id IN (SELECT id FROM school_day " +
            "WHERE class_id = :classId AND is_next_school_day = 1)",
    )
    abstract suspend fun deleteLookaheadEventsOf(classId: Long)

    @Query(
        "DELETE FROM lesson WHERE day_id IN (SELECT id FROM school_day " +
            "WHERE class_id = :classId AND is_next_school_day = 1)",
    )
    abstract suspend fun deleteLookaheadLessonsOf(classId: Long)

    @Query("DELETE FROM school_day WHERE class_id = :classId AND is_next_school_day = 1")
    abstract suspend fun deleteLookaheadDaysOf(classId: Long)

    /**
     * Swaps one school year of one class atomically, leaving its other years be.
     *
     * Wipe-and-reinsert rather than upsert, and that has not changed: the
     * server owns the schedule completely, and a lesson deleted upstream has no
     * key the client could use to notice its absence. What changed is the
     * *extent* of the wipe. It used to be the class — which is why the calendar
     * could only ever hold the current year, and why scrolling to the next one
     * would have destroyed this one on arrival. Now it is the window, so two
     * years sit side by side and a sync of either leaves the other alone.
     *
     * One transaction, so readers see the old year or the new one and never a
     * half-empty week — which matters because the widget can wake up mid-sync.
     *
     * The window row is written last, inside the same transaction: it is the
     * claim «this year is cached», and a claim that outran the rows it is about
     * would leave the calendar reporting a fetched year over an empty grid.
     *
     * @param nextSchoolDay the lookahead day, already flagged; pass `null` to
     *   leave the stored one alone. Only the sync of the year that holds today
     *   passes one, because that row answers «what is the next school day» from
     *   *now*, and a fetch of 2031 has no business rewriting it. In practice
     *   nothing passes one at all: a window that runs to the end of the school
     *   year leaves the server nothing to resolve past it (see the ranged
     *   deletes above). The guard stays because `next_school_day` is in every
     *   bundle and any window narrower than the year fills it, which is one
     *   `days=` away — and because the wrong year writing it is the mistake
     *   that would be invisible on screen.
     */
    @Transaction
    open suspend fun replaceWindow(
        schoolClass: SchoolClassEntity,
        window: SyncedWindowEntity,
        days: List<SchoolDayRecord>,
        nextSchoolDay: SchoolDayRecord?,
    ) {
        val classId = schoolClass.id
        deleteHomeworkBetween(classId, window.startsOn, window.endsOn)
        deleteEventsBetween(classId, window.startsOn, window.endsOn)
        deleteLessonsBetween(classId, window.startsOn, window.endsOn)
        deleteDaysBetween(classId, window.startsOn, window.endsOn)
        if (nextSchoolDay != null) deleteLookaheadOf(classId)
        insertSchoolClass(schoolClass)
        (days + listOfNotNull(nextSchoolDay)).forEach { record ->
            // Re-stamped rather than trusted: the class row being written is
            // the one authority on whose window this is, so a record built for
            // another class cannot be filed under this one's id.
            val dayId = insertDay(record.day.copy(classId = classId))
            insertLessons(record.lessons.map { it.copy(dayId = dayId) })
            insertEvents(record.events.map { it.copy(dayId = dayId) })
            insertHomework(record.homework.map { it.copy(dayId = dayId) })
        }
        insertWindow(window.copy(classId = classId))
    }

    /**
     * Drops one school year of one class, rows and claim together.
     *
     * What the cap is spent through: the cache keeps a few years, not every
     * year anybody ever scrolled past, and a year dropped without its
     * `synced_window` row would be a claim about an empty range — a calendar
     * that says it has the data and draws nothing.
     */
    @Transaction
    open suspend fun dropWindow(classId: Long, window: SyncedWindowEntity) {
        deleteHomeworkBetween(classId, window.startsOn, window.endsOn)
        deleteEventsBetween(classId, window.startsOn, window.endsOn)
        deleteLessonsBetween(classId, window.startsOn, window.endsOn)
        deleteDaysBetween(classId, window.startsOn, window.endsOn)
        deleteWindow(classId, window.openingYear)
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
        // Before the class row, and never after it: a window left standing over
        // no days is a claim that the year is cached, and the calendar believes
        // claims rather than counting rows — which is the whole reason the
        // table exists.
        deleteWindowsOf(classId)
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
     * class is left lands afterwards and `replaceWindow` re-creates the whole
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
        deleteWindowsOutside(keep)
        deleteSchoolClassesOutside(keep)
    }

    /** Empties the whole cache, every class at once. Used on full sign-out. */
    @Transaction
    open suspend fun clearAll() {
        deleteAllHomework()
        deleteAllEvents()
        deleteAllLessons()
        deleteAllDays()
        deleteAllWindows()
        deleteAllSchoolClasses()
    }
}

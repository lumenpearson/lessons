package com.lumenpearson.lessons.core.data.database

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The cache, in memory, for tests that need one that actually remembers.
 *
 * Only the abstract members are implemented, which is the point: `replaceWindow`,
 * `dropWindow`, `clear`, `clearAll`, `deleteLookaheadOf` and `snapshot` are
 * concrete on [TimetableDao], so a test written against this exercises the
 * **real** orchestration — the wipe order, the per-class subqueries, the ranged
 * deletes that spare the lookahead row, the day ids stamped onto children —
 * rather than a second implementation of it that could agree with the first
 * while both are wrong.
 *
 * The claim in that first sentence was untrue once, and quietly: this fake
 * overrode `deleteLookaheadOf` and deleted the day's children, which the one
 * shipped statement did not. Anything it does more thoroughly than the real
 * query is a defect no test in this module can see.
 *
 * Nothing here is thread-safe, and it does not need to be: the tests drive it
 * from one coroutine at a time.
 */
internal class InMemoryTimetableDao : TimetableDao() {

    private val classes = mutableListOf<SchoolClassEntity>()
    private val days = mutableListOf<SchoolDayEntity>()
    private val lessons = mutableListOf<LessonEntity>()
    private val events = mutableListOf<EventEntity>()
    private val homework = mutableListOf<HomeworkEntity>()
    private val windows = mutableListOf<SyncedWindowEntity>()
    private var nextDayId = 1L

    /**
     * Bumped by every write, so the observers re-read.
     *
     * Room invalidates by table; one counter for the lot is coarser and enough,
     * because the tables here are only ever written together.
     */
    private val revision = MutableStateFlow(0)

    private fun touch() {
        revision.value += 1
    }

    private fun detailsOf(classId: Long, lookahead: Boolean): List<SchoolDayWithDetails> = days
        .filter { it.classId == classId && it.isNextSchoolDay == lookahead }
        .sortedBy { it.date }
        .map { day ->
            SchoolDayWithDetails(
                day = day,
                lessons = lessons.filter { it.dayId == day.id },
                events = events.filter { it.dayId == day.id },
                homework = homework.filter { it.dayId == day.id },
            )
        }

    private fun dayIdsOf(classId: Long): Set<Long> =
        days.filter { it.classId == classId }.map { it.id }.toSet()

    override fun observeSchoolClass(classId: Long): Flow<SchoolClassEntity?> =
        revision.map { classes.firstOrNull { row -> row.id == classId } }

    override fun observeDays(classId: Long): Flow<List<SchoolDayWithDetails>> =
        revision.map { detailsOf(classId, lookahead = false) }

    override fun observeNextSchoolDay(classId: Long): Flow<SchoolDayWithDetails?> =
        revision.map { detailsOf(classId, lookahead = true).firstOrNull() }

    override suspend fun schoolClass(classId: Long): SchoolClassEntity? =
        classes.firstOrNull { it.id == classId }

    override suspend fun touchSyncedAt(classId: Long, millis: Long): Int {
        val at = classes.indexOfFirst { it.id == classId }
        if (at < 0) return 0
        classes[at] = classes[at].copy(syncedAtEpochMillis = millis)
        touch()
        return 1
    }

    override suspend fun days(classId: Long): List<SchoolDayWithDetails> =
        detailsOf(classId, lookahead = false).also { dayRowsRead += it.size }

    override suspend fun daysBetween(
        classId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<SchoolDayWithDetails> = detailsOf(classId, lookahead = false)
        .filter { it.day.date in from..to }
        .also { dayRowsRead += it.size }

    /**
     * The Kotlin twin of the `EXISTS` clause on the real query, cancelled
     * lessons and all — `SchoolDay.hasLessons` counts only lessons that
     * actually take place, and a day struck out entirely is not one to point
     * the next-school-day at.
     */
    override suspend fun firstTeachingDayAfter(
        classId: Long,
        after: LocalDate,
    ): SchoolDayWithDetails? = detailsOf(classId, lookahead = false)
        .firstOrNull { day ->
            day.day.date > after && day.lessons.any { !it.isCancelled }
        }
        ?.also { dayRowsRead += 1 }

    override suspend fun nextSchoolDay(classId: Long): SchoolDayWithDetails? =
        detailsOf(classId, lookahead = true).firstOrNull()

    override suspend fun insertSchoolClass(entity: SchoolClassEntity) {
        classes.removeAll { it.id == entity.id }
        classes += entity
        touch()
    }

    override suspend fun insertDay(entity: SchoolDayEntity): Long {
        val id = nextDayId++
        days += entity.copy(id = id)
        touch()
        return id
    }

    override suspend fun insertLessons(entities: List<LessonEntity>) {
        lessons += entities
        touch()
    }

    override suspend fun insertEvents(entities: List<EventEntity>) {
        events += entities
        touch()
    }

    override suspend fun insertHomework(entities: List<HomeworkEntity>) {
        homework += entities
        touch()
    }

    override fun observeWindows(classId: Long): Flow<List<SyncedWindowEntity>> =
        revision.map { windowsOf(classId) }

    override suspend fun windows(classId: Long): List<SyncedWindowEntity> = windowsOf(classId)

    private fun windowsOf(classId: Long): List<SyncedWindowEntity> = windows
        .filter { it.classId == classId }
        .sortedByDescending { it.openingYear }

    override suspend fun insertWindow(entity: SyncedWindowEntity) {
        windows.removeAll { it.classId == entity.classId && it.openingYear == entity.openingYear }
        windows += entity
        touch()
    }

    override suspend fun deleteWindow(classId: Long, openingYear: Int) {
        windows.removeAll { it.classId == classId && it.openingYear == openingYear }
        touch()
    }

    override suspend fun deleteWindowsOf(classId: Long) {
        windows.removeAll { it.classId == classId }
        touch()
    }

    override suspend fun deleteWindowsOutside(keep: Collection<Long>) {
        windows.removeAll { it.classId !in keep }
        touch()
    }

    override suspend fun deleteAllWindows() {
        windows.clear()
        touch()
    }

    /**
     * The Kotlin twins of the ranged deletes, lookahead exclusion and all.
     *
     * That exclusion is the half worth copying exactly: the stored lookahead
     * row sits at a date that also falls inside some window, and a delete that
     * swept it up while replacing a neighbouring year would take away the one
     * row that answers `schoolDayAfter` across a gap.
     */
    private fun dayIdsBetween(classId: Long, from: LocalDate, to: LocalDate): Set<Long> = days
        .filter { it.classId == classId && !it.isNextSchoolDay && it.date in from..to }
        .map { it.id }
        .toSet()

    override suspend fun deleteHomeworkBetween(classId: Long, from: LocalDate, to: LocalDate) {
        val owned = dayIdsBetween(classId, from, to)
        homework.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteEventsBetween(classId: Long, from: LocalDate, to: LocalDate) {
        val owned = dayIdsBetween(classId, from, to)
        events.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteLessonsBetween(classId: Long, from: LocalDate, to: LocalDate) {
        val owned = dayIdsBetween(classId, from, to)
        lessons.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteDaysBetween(classId: Long, from: LocalDate, to: LocalDate) {
        val owned = dayIdsBetween(classId, from, to)
        days.removeAll { it.id in owned }
        touch()
    }

    /**
     * The Kotlin twins of the lookahead deletes, one statement each.
     *
     * `deleteLookaheadOf` is concrete on [TimetableDao] and is therefore
     * inherited rather than written again here. While it was one abstract
     * query, this fake answered it by deleting the children too — which is
     * more than the shipped SQL did, and it is the divergence that hid the
     * real query's reliance on `PRAGMA foreign_keys` from every unit test.
     */
    private fun lookaheadDayIdsOf(classId: Long): Set<Long> = days
        .filter { it.classId == classId && it.isNextSchoolDay }
        .map { it.id }
        .toSet()

    override suspend fun deleteLookaheadHomeworkOf(classId: Long) {
        val owned = lookaheadDayIdsOf(classId)
        homework.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteLookaheadEventsOf(classId: Long) {
        val owned = lookaheadDayIdsOf(classId)
        events.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteLookaheadLessonsOf(classId: Long) {
        val owned = lookaheadDayIdsOf(classId)
        lessons.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteLookaheadDaysOf(classId: Long) {
        days.removeAll { it.classId == classId && it.isNextSchoolDay }
        touch()
    }

    override suspend fun deleteHomeworkOf(classId: Long) {
        val owned = dayIdsOf(classId)
        homework.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteEventsOf(classId: Long) {
        val owned = dayIdsOf(classId)
        events.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteLessonsOf(classId: Long) {
        val owned = dayIdsOf(classId)
        lessons.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteDaysOf(classId: Long) {
        days.removeAll { it.classId == classId }
        touch()
    }

    override suspend fun deleteSchoolClass(classId: Long) {
        classes.removeAll { it.id == classId }
        touch()
    }

    override suspend fun deleteAllHomework() {
        homework.clear()
        touch()
    }

    override suspend fun deleteAllEvents() {
        events.clear()
        touch()
    }

    override suspend fun deleteAllLessons() {
        lessons.clear()
        touch()
    }

    override suspend fun deleteAllDays() {
        days.clear()
        touch()
    }

    override suspend fun deleteAllSchoolClasses() {
        classes.clear()
        touch()
    }

    override suspend fun deleteHomeworkOutside(keep: Collection<Long>) {
        val owned = daysOutside(keep)
        homework.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteEventsOutside(keep: Collection<Long>) {
        val owned = daysOutside(keep)
        events.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteLessonsOutside(keep: Collection<Long>) {
        val owned = daysOutside(keep)
        lessons.removeAll { it.dayId in owned }
        touch()
    }

    override suspend fun deleteDaysOutside(keep: Collection<Long>) {
        days.removeAll { it.classId !in keep }
        touch()
    }

    override suspend fun deleteSchoolClassesOutside(keep: Collection<Long>) {
        classes.removeAll { it.id !in keep }
        touch()
    }

    private fun daysOutside(keep: Collection<Long>): Set<Long> =
        days.filter { it.classId !in keep }.map { it.id }.toSet()

    // -- what the assertions look at ---------------------------------------

    /** Every class row currently cached, in insertion order. */
    fun cachedClassIds(): List<Long> = classes.map { it.id }

    /** How many day rows this class has, lookahead day included. */
    fun dayCountOf(classId: Long): Int = days.count { it.classId == classId }

    /** Which school years this class claims to hold, newest first. */
    fun cachedYearsOf(classId: Long): List<Int> = windowsOf(classId).map { it.openingYear }

    /**
     * How many day rows the one-shot reads have handed back since this fake
     * was built.
     *
     * The cost a caller pays, in the only unit that matters here: each of
     * those rows drags its lessons, events and homework with it through the
     * `@Relation`s. A bounded read is supposed to be a fortnight of them and
     * the whole-year read is some two hundred.
     */
    var dayRowsRead: Int = 0
        private set

    /** How many lesson rows hang off this class's days. */
    fun lessonCountOf(classId: Long): Int {
        val owned = dayIdsOf(classId)
        return lessons.count { it.dayId in owned }
    }
}

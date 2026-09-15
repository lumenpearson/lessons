package com.lumenpearson.lessons.core.data.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The cache, in memory, for tests that need one that actually remembers.
 *
 * Only the abstract members are implemented, which is the point: `replaceAll`,
 * `clear`, `clearAll` and `snapshot` are concrete on [TimetableDao], so a test
 * written against this exercises the **real** orchestration — the wipe order,
 * the per-class subqueries, the day ids stamped onto children — rather than a
 * second implementation of it that could agree with the first while both are
 * wrong.
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

    override suspend fun days(classId: Long): List<SchoolDayWithDetails> =
        detailsOf(classId, lookahead = false)

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

    // -- what the assertions look at ---------------------------------------

    /** Every class row currently cached, in insertion order. */
    fun cachedClassIds(): List<Long> = classes.map { it.id }

    /** How many day rows this class has, lookahead day included. */
    fun dayCountOf(classId: Long): Int = days.count { it.classId == classId }

    /** How many lesson rows hang off this class's days. */
    fun lessonCountOf(classId: Long): Int {
        val owned = dayIdsOf(classId)
        return lessons.count { it.dayId in owned }
    }
}

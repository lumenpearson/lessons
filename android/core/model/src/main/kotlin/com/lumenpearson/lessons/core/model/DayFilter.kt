package com.lumenpearson.lessons.core.model

/**
 * What a reader is looking for when they narrow a calendar down.
 *
 * Four facets rather than a free-text search, because the question a calendar
 * is asked is always one of these: «where is the homework», «which days are
 * marked», «when do I actually have lessons». A search box would answer none of
 * them without typing.
 *
 * **Several chosen facets are an OR, not an AND.** «с ДЗ» and «с событиями»
 * together mean «покажи, где есть хоть что-то из этого» — which is what picking
 * two of four reads as, and what makes the chips useful rather than a way to
 * end up with an empty month. An AND would mean each extra chip could only ever
 * remove days, so the second press would almost always empty the screen.
 *
 * Nothing chosen means no filter at all, rather than «show nothing»: an empty
 * set is the resting state of this feature and has to be the harmless one.
 */
enum class DayFilter {
    /** Days that actually teach something. */
    HAS_LESSONS,

    /** Days something is due on. */
    HAS_HOMEWORK,

    /** Days with an event on the timeline — an excursion, an exam, a meeting. */
    HAS_EVENTS,

    /**
     * Days somebody decided something about: a holiday, remote teaching,
     * self-study, a day off, a shortened day.
     *
     * Not the same as «no lessons». An ordinary Sunday is empty because the
     * timetable is empty; a marked day is empty, or different, because a person
     * said so — and «какие дни отмечены» is the question an admin asks when
     * checking their own work.
     */
    MARKED,
    ;

    /** Whether this one facet is true of [day]. */
    fun matches(day: SchoolDay): Boolean = when (this) {
        HAS_LESSONS -> day.hasLessons
        HAS_HOMEWORK -> day.homework.isNotEmpty()
        HAS_EVENTS -> day.events.isNotEmpty()
        // The holiday the server *named* does not count: «День учителя» is a
        // badge on an ordinary Wednesday, and a filter for marked days that
        // returned it would return a date nobody in this class marked.
        MARKED -> day.kind != DayKind.NORMAL
    }
}

/**
 * Whether [day] survives [filters].
 *
 * A date the cache does not reach is `null` and never survives a filter: it is
 * «not loaded», and answering «yes, this matches» about a day nothing is known
 * of would put an empty cell under a chip that promised homework.
 */
fun SchoolDay?.matches(filters: Set<DayFilter>): Boolean {
    if (filters.isEmpty()) return true
    val day = this ?: return false
    return filters.any { it.matches(day) }
}

/**
 * How a list of days is ordered, where a list is what is being drawn.
 *
 * A month grid cannot be sorted — a month is a month, and moving the 14th
 * before the 3rd would stop it being a calendar — so this only reaches the
 * views that are genuinely lists.
 */
enum class DayOrder {
    /** Oldest first: how a calendar reads, and the default. */
    DATE_ASC,

    /** Newest first, for looking back over what has just happened. */
    DATE_DESC,

    /**
     * The busiest days first.
     *
     * Ties break by date ascending rather than arbitrarily: two days with five
     * lessons each in a stable order is a list somebody can scroll twice and
     * recognise, and `sortedBy` alone does not promise that across platforms.
     */
    BUSIEST_FIRST,
    ;

    fun sort(days: List<SchoolDay>): List<SchoolDay> = when (this) {
        DATE_ASC -> days.sortedBy { it.date }
        DATE_DESC -> days.sortedByDescending { it.date }
        BUSIEST_FIRST -> days.sortedWith(
            compareByDescending<SchoolDay> { it.activeLessons.size }.thenBy { it.date },
        )
    }
}

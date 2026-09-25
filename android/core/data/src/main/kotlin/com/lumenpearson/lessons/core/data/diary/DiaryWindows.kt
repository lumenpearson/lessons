package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * How long a read of the diary counts as current.
 *
 * One number for two decisions that have to agree: whether opening the app
 * refreshes the week (`DiaryImport.refreshIfStale`, the only «activity» the
 * phone performs — nothing reads the diary in the background), and whether the
 * diary screen, seeded from the cache, asks again at all. Half an hour keeps an
 * import that just finished from being repeated by the screen it lands on, and
 * still means a family opening the app after lunch sees the afternoon's
 * homework.
 */
val DiaryFreshFor: Duration = Duration.ofMinutes(30)

/** Whether something read at [loadedAt] is still current at [now]; see [DiaryFreshFor]. */
fun diaryIsFresh(loadedAt: Instant?, now: Instant): Boolean =
    loadedAt != null && !loadedAt.isAfter(now) && Duration.between(loadedAt, now) < DiaryFreshFor

/** A span of days, inclusive at both ends. */
data class DiaryDateRange(val from: LocalDate, val to: LocalDate)

/**
 * The date arithmetic the diary screen and the import both ask, in one place.
 *
 * It has to be one place: the import fills the cache for the ranges the screen
 * is about to ask for, and a range worked out twice is a range that will one
 * day be worked out differently — a cache that is never hit, with nothing to
 * say so. The screen's own helpers in `:app` delegate here.
 */
object DiaryWindows {

    /**
     * The date it is where the diary is, not where the phone is.
     *
     * [zone] is the session's (`DiaryTarget.zoneId()`), which is the zone the
     * server cuts the diary's days at, so a pupil in Tomsk and the server agree
     * on which day is today.
     */
    fun today(zone: ZoneId, clock: Clock = Clock.systemUTC()): LocalDate =
        LocalDateTime.ofInstant(clock.instant(), zone).toLocalDate()

    /** Monday of the week [date] falls in; the week view's anchor and the cache's key. */
    fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Whether [from] .. [to] is a run of whole Monday-to-Sunday weeks — the only
     * ranges the cache keeps. A range cut anywhere else would stamp a week as
     * fetched when only part of it was.
     */
    fun isWholeWeeks(from: LocalDate, to: LocalDate): Boolean =
        from.dayOfWeek == DayOfWeek.MONDAY &&
            to.dayOfWeek == DayOfWeek.SUNDAY &&
            !to.isBefore(from)

    /**
     * The marks window: the current term, cut to what the server will answer.
     *
     * The server refuses anything wider than [DiaryRepository.MAX_RANGE_DAYS],
     * and a quarter is routinely longer, so the rule is, in order:
     *
     *  * end at today, or at the end of the term when it is already over —
     *    marks not yet given return nothing and say nothing;
     *  * start at the beginning of the term, when that fits;
     *  * otherwise start as far back as the limit allows, so what is shown is
     *    the latest part of the term rather than its first weeks.
     */
    fun gradeWindow(today: LocalDate, period: DiaryPeriod? = null): DiaryDateRange {
        val limit = DiaryRepository.MAX_RANGE_DAYS
        val end = period?.endsOn?.takeIf { it.isBefore(today) } ?: today
        val widest = end.minusDays(limit)
        val start = period?.startsOn?.takeIf { it.isAfter(widest) } ?: widest
        // A term whose start is somehow after its end cannot narrow anything.
        return DiaryDateRange(from = minOf(start, end), to = end)
    }
}

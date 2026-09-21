package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.model.Timetable
import kotlinx.coroutines.flow.Flow

/**
 * The cached timetable, and the only way to fill it.
 *
 * Offline-first: [timetable] is fed from Room, never from the network, so every
 * consumer keeps working with the radio off and no screen has to think about
 * loading states beyond "have we ever synced".
 */
interface TimetableRepository {

    /**
     * The cache, live.
     *
     * Emits `null` until the first successful sync, which is how the UI tells
     * "no data yet" from "a genuinely empty week" - an empty [Timetable] means
     * the school has nothing scheduled, not that the app is uninitialised.
     */
    val timetable: Flow<Timetable?>

    /**
     * One value, no subscription.
     *
     * The Glance widget and the sync worker run outside any lifecycle and would
     * otherwise have to collect a flow just to cancel it again.
     */
    suspend fun snapshot(): Timetable?

    /**
     * [snapshot], but only the days anything but the calendar ever asks about:
     * from the Monday of the current week (or yesterday, whichever is further
     * back) to eight days ahead, as the school reckons the date.
     *
     * A second method rather than a parameter on [snapshot], because the two
     * do not answer the same question and a caller has to choose on purpose.
     * What comes back **is** a [Timetable], and its `days` do not span the
     * year: `ScheduleEngine` and `AlertPlanner` read a date that is not in
     * them as «no data», which inside the bound is the truth and outside it is
     * a lie — a July reading would report the whole of September empty. So
     * this is for a reader whose every question is about the next few days,
     * and the type cannot enforce that.
     *
     * It is a `Timetable` all the same rather than some bounded twin of one,
     * because the alternative is a second set of rendering and planning code
     * written against the twin, and two of those disagree within a month. The
     * one thing the bound would otherwise break is `schoolDayAfter`, which has
     * to reach the first of September from July and the Monday back from the
     * middle of the winter holidays: the answer is carried as `nextSchoolDay`,
     * resolved from the cache beyond the bound, so it keeps answering exactly
     * what the whole year answers for every date inside it.
     *
     * Same contract as [snapshot] otherwise — `null` until the first sync.
     */
    suspend fun snapshotAroundToday(): Timetable?

    /**
     * Which school years of the shown class are actually in the cache.
     *
     * Named by the calendar year each one opens in — see
     * `SchoolYear.openingYearOf`. This is what tells «this month has no
     * lessons» from «this month has not been fetched», which used to be the
     * same thing on screen and read as a timetable that stops.
     *
     * Empty before the first sync, and empty when this device is in no class.
     */
    val syncedYears: Flow<Set<Int>>

    /**
     * Fetches the school year that holds today and writes it over that year.
     *
     * The other years in the cache are left alone. It used to take a number of
     * days and replace the whole class with them, which is why the calendar
     * could only ever hold one year: scrolling to the next one would have
     * destroyed the current one on arrival. A year names itself, so there is
     * nothing left for a caller to choose — and a parameter nothing can honour
     * is worse than no parameter.
     *
     * Never throws: network trouble is normal on a phone in a school building,
     * so it is reported as [SyncResult] instead. Callers that want the widget
     * redrawn afterwards should go through `SyncScheduler.syncNow`, which
     * broadcasts on success.
     */
    suspend fun refresh(): SyncResult

    /**
     * The same, for a year the reader has scrolled to rather than the one they
     * are in.
     *
     * @param openingYear the calendar year the school year opens in: 2026 is
     *   September 2026 to May 2027, which is how a school says it too.
     */
    suspend fun refreshYear(openingYear: Int): SyncResult

    /**
     * Forgets the cached window of every class this device has left.
     *
     * A sweep rather than part of leaving: a sync already in flight when a
     * class is left lands after the wipe and re-creates its whole window. No
     * screen can draw it — every read is filtered by the class on screen — so
     * what is left is a year of a timetable kept on a phone that asked to stop
     * holding it, until something sweeps.
     *
     * @param keep the classes the device is still in. Empty means it is in
     * none, and the whole cache goes.
     */
    suspend fun forgetClassesOtherThan(keep: Set<Long>)
}

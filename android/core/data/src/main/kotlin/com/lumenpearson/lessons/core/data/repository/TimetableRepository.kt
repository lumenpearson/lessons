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
     * Fetches [days] days from the Monday of the current week and replaces the
     * cache with them.
     *
     * Never throws: network trouble is normal on a phone in a school building,
     * so it is reported as [SyncResult] instead. Callers that want the widget
     * redrawn afterwards should go through `SyncScheduler.syncNow`, which
     * broadcasts on success.
     */
    suspend fun refresh(days: Int = 31): SyncResult

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

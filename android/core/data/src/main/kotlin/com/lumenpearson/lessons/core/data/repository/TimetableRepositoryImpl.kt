package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.SyncedWindowEntity
import com.lumenpearson.lessons.core.data.database.TimetableDao
import com.lumenpearson.lessons.core.data.database.buildTimetable
import com.lumenpearson.lessons.core.data.database.toEntity
import com.lumenpearson.lessons.core.data.database.toRecord
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemote
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemoteResult
import com.lumenpearson.lessons.core.data.network.v2.RemoteError
import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.network.ServerNeedsHttpsException
import com.lumenpearson.lessons.core.model.SchoolYear
import com.lumenpearson.lessons.core.model.Timetable
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed implementation: the network writes, the database reads, and the
 * two never meet in a consumer's code.
 *
 * Internal because the container hands out the interface; nothing outside this
 * module should be able to build one with its own DAO.
 *
 * @param clock injected so tests can pin "today" without waiting for midnight.
 */
internal class TimetableRepositoryImpl(
    private val dao: TimetableDao,
    private val remote: ScheduleRemote,
    /**
     * The class being shown, or `null` when this device is in none.
     *
     * Every read below is filtered by it. The cache holds a window per joined
     * class, so without the filter the screens would draw whichever class's
     * Monday the database happened to return first — and the two look exactly
     * alike on screen, which is the failure mode worth designing against.
     *
     * A flow rather than a value because switching classes has to redraw what is
     * already on screen, and a `val` read once at construction would keep the
     * previous class up until the process died.
     */
    private val activeClassId: Flow<Long?>,
    /**
     * device clock: the seam tests pin "today" through, and the only zone
     * available before this phone has cached a class to take one from.
     *
     * Nothing here reads a date off it directly. `todayAtSchool` re-zones it to
     * the cached class first, and the one other use — `clock.millis()` — is an
     * instant, which has no zone to be wrong about.
     */
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Called after a sync writes something. The widget lives in another module
     * and cannot be called directly, so the container hands in the broadcast.
     * Without it, pull-to-refresh filled the app and left the widget empty —
     * which made the one remedy a user would try look like it did nothing.
     */
    private val onDataChanged: () -> Unit = {},
    /**
     * Called after a sync found the cache already current — a `304`.
     *
     * Deliberately not [onDataChanged]: nothing the widget draws has moved, and
     * a redraw per poll is the cost the conditional request exists to avoid. It
     * exists for the one thing that *is* time-dependent even when the data is
     * not — the notification alarm chain, which is one alarm long and re-arms
     * itself only when an alarm fires. A break longer than the planner's
     * horizon leaves nothing armed behind it, and on a phone whose window has
     * not changed every poll is a `304`, so the sync was the last thing that
     * could have noticed and it was the one path that said nothing.
     *
     * Cheap on purpose, because this runs on every poll of a phone whose
     * schedule is not moving: the container wires it to a check that an alarm
     * is standing, not to a full re-plan off the cached year.
     */
    private val onNothingChanged: () -> Unit = {},
    /**
     * Called when the server refuses this device's token.
     *
     * The session and the cache are not this class's to drop, and nothing else
     * was dropping them: a `401` became a snackbar and the app carried on
     * showing a class it had been thrown out of. It is the one failure with a
     * single correct answer — the token is gone server-side and cannot come
     * back — so the cure has to be automatic rather than a sentence asking the
     * user to sign out by hand.
     */
    private val onTokenRejected: suspend () -> Unit = {},
    /**
     * Where the last window's ETag lives between syncs; see [BundleTagStore].
     *
     * Defaulted to the forgetful one so a test that is not about conditional
     * requests reads exactly as it did before.
     */
    private val bundleTags: BundleTagStore = BundleTagStore.None,
    private val membership: ScheduleMembership? = null,
) : TimetableRepository, TimetableCache {

    /**
     * The class row is the gate: it only exists after a sync, so `null` before
     * the first one falls out of the data model instead of needing a flag.
     * `distinctUntilChanged` keeps an unrelated write - a settings change cannot
     * touch these tables, but a re-sync with identical content can - from
     * redrawing every screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val timetable: Flow<Timetable?> = activeClassId.flatMapLatest { classId ->
        if (classId == null) {
            flowOf(null)
        } else {
            combine(
                dao.observeSchoolClass(classId),
                dao.observeDays(classId),
                dao.observeNextSchoolDay(classId),
            ) { schoolClass, days, nextSchoolDay ->
                schoolClass?.let { buildTimetable(it, days, nextSchoolDay) }
            }
        }
    }.distinctUntilChanged()

    /**
     * Which school years this class has actually fetched, live.
     *
     * The calendar's answer to «is this month empty, or merely not here yet».
     * Before the cache held more than one year the two were the same question —
     * there was one window and everything outside it was «Нет данных», which on
     * screen is indistinguishable from a week with no lessons in it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val syncedYears: Flow<Set<Int>> = activeClassId.flatMapLatest { classId ->
        if (classId == null) {
            flowOf(emptySet())
        } else {
            dao.observeWindows(classId).map { windows ->
                windows.map { it.openingYear }.toSet()
            }
        }
    }.distinctUntilChanged()

    override suspend fun snapshot(): Timetable? = withContext(ioDispatcher) {
        val classId = activeClassId.first() ?: return@withContext null
        // One transaction for all three reads: `replaceWindow` swaps the class row
        // and a year's days together, and a reader interleaved between two separate
        // statements can take the class from before the swap and the days from
        // after it. The widget redraws on the sync broadcast, i.e. by
        // construction at exactly that moment.
        val snapshot = dao.snapshot(classId) ?: return@withContext null
        buildTimetable(snapshot.schoolClass, snapshot.days, snapshot.nextSchoolDay)
    }

    override suspend fun snapshotAroundToday(): Timetable? = withContext(ioDispatcher) {
        val classId = activeClassId.first() ?: return@withContext null
        // The class's zone, not the device's, and therefore one single-row read
        // before the transaction that reads the row again: the bound is a
        // handful of dates around «today», and a phone in Moscow looking at a
        // school in Novosibirsk is on the wrong date for five hours of every
        // evening. Two indexed reads of one row against two hundred days of
        // lessons is the trade being made here.
        val bound = CachedWindow.around(todayAtSchool())
        val snapshot = dao.snapshotBetween(classId, bound.start, bound.endInclusive)
            ?: return@withContext null
        buildTimetable(snapshot.schoolClass, snapshot.days, snapshot.nextSchoolDay)
    }

    /**
     * Whether this device is actually holding the window the tag is about.
     *
     * Asked only on a `304`, so it costs one indexed read on the path that was
     * already the cheap one. It asks about *that* window rather than about the
     * class: with a year per row, a phone holding 2026 and sending a tag for
     * 2027 would otherwise be told «nothing changed» about a year it has never
     * fetched, and the calendar would sit on an empty grid reporting a
     * successful sync — which is the same failure the class-wide check was
     * written for, one level down.
     */
    private suspend fun holdsWindow(classId: Long, openingYear: Int): Boolean =
        dao.windows(classId).any { it.openingYear == openingYear }

    override suspend fun refresh(): SyncResult = withContext(ioDispatcher) {
        syncYear(SchoolYear.openingYearOf(todayAtSchool()))
    }

    override suspend fun refreshYear(openingYear: Int): SyncResult = withContext(ioDispatcher) {
        syncYear(openingYear)
    }

    /**
     * One sync of a class's year at a time; a second asked for while the first
     * is on the wire takes the first one's answer (#171).
     *
     * A join asks twice in the same second — the join screen's own refresh, and
     * the one-off worker the class switch schedules — and neither had stored an
     * `ETag` yet, so each downloaded the whole school year. Keyed like the tag,
     * by class and year, so a switch between classes is never coalesced into
     * the class being left.
     */
    private data class SyncRequest(val classId: Long?, val year: Int, val credentials: RequestCredentials?)

    private val flights = SingleFlight<SyncRequest, SyncResult>()

    private suspend fun syncYear(openingYear: Int): SyncResult {
        val owner = membership?.capture()
        if (membership != null && owner == null) return SyncResult.Unauthorised
        val request = SyncRequest(owner?.classId ?: activeClassId.first(), openingYear, owner?.credentials)
        return flights.run(request) { fetchYear(request) }
    }

    /**
     * Fetches one school year and writes it over whatever that year held.
     *
     * The window is the year exactly — `SchoolYear.boundsOf(openingYear)` — and
     * nothing anchors it to today any more. The Monday anchor that used to be
     * here existed for one reason: the write `replaceWindow` replaced wiped the
     * whole class, so a sync mid-week destroyed the days already past unless
     * the request reached back to cover them. Replacing a window instead of a
     * class takes that away with it, and takes the summer bug with it too — the
     * anchor applied in July reached back far enough to ask for some 320 days,
     * past what the server accepts, and the window came back silently clipped
     * in April.
     *
     * Resolving a year costs the server the same handful of queries as
     * resolving a month: the weekly template is loaded once and the rest is
     * arithmetic. What it costs is payload, once per year visited — which is
     * what the `ETag` is for.
     */
    private suspend fun fetchYear(request: SyncRequest): SyncResult {
        var observation = CallObservation(request.credentials)
        return try {
            val bounds = SchoolYear.boundsOf(request.year)
            val signature = "${request.classId ?: 0L}|${request.year}"
            var response = remote.window(request.year, bundleTags.tagFor(signature), observation)
            if (response is ScheduleRemoteResult.NotModified &&
                !(request.classId != null && holdsWindow(request.classId, request.year))
            ) {
                // A persisted tag can outlive Room; recovery is one unconditional request only.
                observation = CallObservation(request.credentials)
                response = remote.window(request.year, null, observation)
            }
            if (response is ScheduleRemoteResult.NotModified) {
                finishUnchanged(request, observation)
            } else {
                val changed = response as ScheduleRemoteResult.Changed
                val result = applying(observation, changed.timetable.schoolClass.id) {
                    storeChanged(changed, request.year, bounds)
                }
                if (result == SyncResult.Success) onDataChanged()
                result
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refusal: RemoteError) {
            when (refusal.reason) {
                "DEVICE_TOKEN_INVALID" -> rejectSentMembership(observation)
                "CLIENT_TOO_OLD" -> SyncResult.ClientTooOld
                else -> SyncResult.Failed(refusal.message ?: "The server refused the sync")
            }
        } catch (_: ServerNeedsHttpsException) {
            SyncResult.NeedsHttps
        } catch (missing: ServerAddressMissingException) {
            SyncResult.NotConfigured
        } catch (io: IOException) {
            SyncResult.Failed(io.message ?: "Network unavailable")
        } catch (unexpected: Exception) {
            SyncResult.Failed(unexpected.message ?: unexpected::class.java.simpleName)
        }
    }

    private suspend fun finishUnchanged(request: SyncRequest, observation: CallObservation): SyncResult {
        val result = applying(observation, request.classId ?: 0L) {
            val classId = request.classId
            if (classId == null || !holdsWindow(classId, request.year)) {
                SyncResult.Failed("The server returned no cached schedule window")
            } else {
                dao.touchSyncedAt(classId, clock.millis())
                SyncResult.Success
            }
        }
        // The unchanged window still needs an armed alarm chain, but never a false freshness claim.
        if (result == SyncResult.Success) onNothingChanged()
        return result
    }

    private suspend fun applying(
        observation: CallObservation,
        classId: Long,
        persist: suspend () -> SyncResult,
    ): SyncResult = membership?.apply(observation.credentials, classId, persist) ?: persist()

    private suspend fun rejectSentMembership(observation: CallObservation): SyncResult = try {
        val rejected = if (membership != null) membership.reject(observation.credentials) else {
            onTokenRejected()
            true
        }
        if (rejected) SyncResult.Unauthorised else SyncResult.Failed("The membership changed during sync")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // A storage failure must not turn a handled remote refusal into a process crash.
        SyncResult.Unauthorised
    }

    private suspend fun storeChanged(
        changed: ScheduleRemoteResult.Changed,
        openingYear: Int,
        bounds: ClosedRange<LocalDate>,
    ): SyncResult {
        val timetable = changed.timetable
        val classId = timetable.schoolClass.id
        dao.replaceWindow(
            schoolClass = timetable.schoolClass.toEntity(timetable.syncedAtEpochMillis),
            window = SyncedWindowEntity(classId, openingYear, bounds.start, bounds.endInclusive, clock.millis()),
            days = timetable.days.map { it.toRecord(classId, isNextSchoolDay = false) },
            // A future year's fetch must not replace the lookahead row for today.
            nextSchoolDay = timetable.nextSchoolDay
                ?.takeIf { openingYear == SchoolYear.openingYearOf(todayAtSchool()) }
                ?.toRecord(classId, isNextSchoolDay = true),
        )
        // Only a window that landed may acquire a validator, under its confirmed class.
        changed.etag?.takeIf { it.isNotBlank() }?.let { bundleTags.remember("$classId|$openingYear", it) }
        runCatching { prune(classId) }
        return SyncResult.Success
    }

    /**
     * Keeps the cache to [MAX_YEARS] school years for this class.
     *
     * A cap rather than nothing, because the years a reader scrolls past are
     * unbounded and each one is some 274 days of lessons, events and homework
     * that every observer of this class re-reads on every write. Three is the
     * number because the question a calendar is actually asked reaches one year
     * either side of the one it is in; a fourth is somebody who scrolled a long
     * way once.
     *
     * **The year that holds today is never dropped**, whatever else is here: it
     * is what the home screen, the widget and every notification read, and
     * evicting it to make room for 2031 would empty the app to fill a screen
     * nobody is looking at any more.
     *
     * What goes is the year *furthest* from it, rather than the one fetched
     * longest ago. Fetch time was the first rule written here and it was wrong
     * twice over. It is not «least recently used» at all — a year visited again
     * and unchanged answers `304`, which touches the class row and not the
     * window's — and it ties: four years fetched inside one millisecond, which
     * is what a fast device does, left the order to whatever the query
     * happened to return, and the query returns the newest year first, so the
     * one just asked for was the one thrown away. Distance is clock-independent
     * and is also the better rule: a reader scrolls away from today and back.
     *
     * A tie in distance — 2025 and 2027 seen from 2026 — keeps the later year,
     * because a school calendar is asked forward far more than back. The past
     * is a record; the future is a plan.
     *
     * The tag goes with the rows. One kept for a dropped year is a `304` over
     * data this phone no longer holds.
     */
    private suspend fun prune(classId: Long) {
        val current = SchoolYear.openingYearOf(todayAtSchool())
        val held = dao.windows(classId)
        if (held.size <= MAX_YEARS) return
        held.filter { it.openingYear != current }
            .sortedWith(
                compareByDescending<SyncedWindowEntity> { abs(it.openingYear - current) }
                    .thenBy { it.openingYear },
            )
            .take(held.size - MAX_YEARS)
            .forEach { window ->
                dao.dropWindow(classId, window)
                bundleTags.forget("$classId|${window.openingYear}")
            }
    }

    /**
     * Rows, claims and tags go together, in all three of these.
     *
     * The tag is the half that used to be left behind. It lives in the
     * preferences, so none of the wipes below could reach it from where they
     * were written — `dao.retainOnly` here, `dao.clear` and `dao.clearAll` over
     * in the session repository — and the store grew by one entry per
     * class-year for the life of the install. Nothing showed: a re-join sends
     * the stale tag, the server matches it, and the `304` is caught by
     * [holdsWindow], which costs one wasted round trip and then heals.
     */
    override suspend fun forgetClassesOtherThan(keep: Set<Long>) {
        withContext(ioDispatcher) {
            dao.retainOnly(keep)
            bundleTags.forgetClassesOtherThan(keep)
        }
    }

    override suspend fun forgetClass(classId: Long) {
        withContext(ioDispatcher) {
            dao.clear(classId)
            bundleTags.forgetClass(classId)
        }
    }

    override suspend fun forgetEverything() {
        withContext(ioDispatcher) {
            dao.clearAll()
            // The sweep with nothing kept, spelled the way the DAO spells it:
            // `retainOnly(emptySet())` is `clearAll` there, and one rule for
            // «everything» is one fewer thing to keep in step.
            bundleTags.forgetClassesOtherThan(emptySet())
        }
    }

    /**
     * Today in the *class's* zone, falling back to the device's only when this
     * phone has not cached a class yet.
     *
     * `LocalDate.now(clock)` on a `systemDefaultZone` clock is the device's
     * date, and the window this date picks is a school year: `SchoolYear`
     * changes windows between 31 May and 1 June. A class in Kaliningrad seen
     * from a phone left on a zone to the east therefore asks, at ten in the
     * evening on the last day of school, for *next* September–May. While the
     * write before `replaceWindow` wiped the whole class first, the app and the
     * widget both fell to «Нет данных» for the rest of that day and stayed there
     * until the device's own date rolled over. `replaceWindow` replaces only the
     * year it is handed, so today's year now survives that, but the refresh still
     * fetches next year in its place.
     *
     * The zone is already cached beside the timetable this call is about to
     * replace, which is one indexed single-row read against a network round
     * trip.
     */
    private suspend fun todayAtSchool(): LocalDate {
        val stored = runCatching {
            activeClassId.first()?.let { dao.schoolClass(it)?.timeZoneId }
        }.getOrNull()
        val zone = stored?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() }
        // device clock: only until this phone has a class to take a zone from.
        return LocalDate.now(if (zone != null) clock.withZone(zone) else clock)
    }

    private companion object {
        /**
         * How many school years one class keeps. See [prune].
         */
        const val MAX_YEARS = 3

    }
}

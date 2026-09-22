package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.SyncedWindowEntity
import com.lumenpearson.lessons.core.data.database.TimetableDao
import com.lumenpearson.lessons.core.data.database.buildTimetable
import com.lumenpearson.lessons.core.data.database.toEntity
import com.lumenpearson.lessons.core.data.database.toRecord
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.network.dto.toDomain
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
import retrofit2.HttpException

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
    private val api: LessonsApi,
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
        // One transaction for all three reads: `replaceAll` swaps the class row
        // and the days together, and a reader interleaved between two separate
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
     * Fetches one school year and writes it over whatever that year held.
     *
     * The window is the year exactly — `SchoolYear.boundsOf(openingYear)` — and
     * nothing anchors it to today any more. The Monday anchor that used to be
     * here existed for one reason: `replaceAll` wiped the whole class, so a
     * sync mid-week destroyed the days already past unless the request reached
     * back to cover them. Replacing a window instead of a class takes that away
     * with it, and takes the summer bug with it too — the anchor applied in
     * July reached back far enough to ask for some 320 days, past what the
     * server accepts, and the window came back silently clipped in April.
     *
     * Resolving a year costs the server the same handful of queries as
     * resolving a month: the weekly template is loaded once and the rest is
     * arithmetic. What it costs is payload, once per year visited — which is
     * what the `ETag` is for.
     */
    private suspend fun syncYear(openingYear: Int): SyncResult {
        return try {
            val bounds = SchoolYear.boundsOf(openingYear)
            val start = bounds.start
            val span = SchoolYear.daysOf(openingYear).coerceIn(MIN_DAYS, MAX_DAYS)
            val classId = activeClassId.first()
            // The tag belongs to one class and one year, and those two numbers
            // are the whole signature now. It used to carry the start date and
            // the span as well, because the window could be any length; a year
            // names itself, and a signature that cannot disagree with the
            // request is one fewer thing to keep in step.
            val signature = "${classId ?: 0L}|$openingYear"
            var response = api.bundle(
                start = start.toString(),
                days = span,
                ifNoneMatch = bundleTags.tagFor(signature),
            )
            if (
                response.code() == HTTP_NOT_MODIFIED &&
                !(classId != null && holdsWindow(classId, openingYear))
            ) {
                // «Nothing changed» is only an answer when there is something
                // for it to be about. The tag lives in the preferences and
                // outlives every wipe of the cache — `clearSession`,
                // `removeSession`, `clearMemberships` and a destructive Room
                // migration all leave it standing — and none of `classId` or
                // `openingYear` moves across a leave and a re-join of the same
                // class. So the phone could send a tag the server still matched
                // while holding nothing at all, and go on reporting a
                // successful sync over «Расписание ещё не загружено» for ever.
                response = api.bundle(start = start.toString(), days = span, ifNoneMatch = null)
            }
            // Retrofit throws `HttpException` for a *body-typed* suspend
            // method and hands a `Response<T>`-typed one the error response
            // verbatim — so the `catch` below, which is where `onTokenRejected`
            // lives, has been unreachable for this call since it started
            // asking «changed since?». A revoked device therefore kept the
            // class, the token and a year of somebody else's timetable, and
            // the screen said «Server returned HTTP 401» in English.
            if (response.code() == HTTP_UNAUTHORISED) {
                runCatching { onTokenRejected() }
                return SyncResult.Unauthorised
            }
            if (response.code() == HTTP_NOT_MODIFIED) {
                // Nothing to write: the window is byte-for-byte what is already
                // cached. The check still happened, though, and «обновлено N
                // назад» is a claim about *that* — left alone it would keep
                // growing on a phone that is syncing perfectly. The widget is
                // deliberately not poked: nothing it draws has changed, and a
                // redraw per poll is the cost this whole request was avoiding.
                classId?.let { dao.touchSyncedAt(it, clock.millis()) }
                // The alarm chain is told anyway, and that is not a
                // contradiction of the line above. The widget draws the data,
                // so unchanged data means nothing to redraw; the chain is a
                // *chain*, one alarm long, and what ends it is not the data
                // changing but time passing over a gap wider than the planner
                // looks ahead — a fortnight of holidays, in which nothing on
                // the server changes and therefore every poll is this branch.
                // Left silent, this is the path that watches the chain die.
                onNothingChanged()
                return SyncResult.Success
            }
            val body = response.body()
                ?: return SyncResult.Failed("Server returned HTTP ${response.code()}")
            val timetable = body.toDomain(fallbackSyncedAtEpochMillis = clock.millis())
            // The class the *server* resolved the token to, not the one this
            // device thinks is active. They are the same except in the seconds
            // around a switch, and taking the local answer there would file one
            // class's window under the other's id — which is the one mistake
            // this cache cannot survive, because both windows look plausible.
            val syncedClassId = timetable.schoolClass.id
            val now = clock.millis()
            dao.replaceWindow(
                schoolClass = timetable.schoolClass.toEntity(timetable.syncedAtEpochMillis),
                window = SyncedWindowEntity(
                    classId = syncedClassId,
                    openingYear = openingYear,
                    startsOn = bounds.start,
                    endsOn = bounds.endInclusive,
                    syncedAtEpochMillis = now,
                ),
                days = timetable.days.map { it.toRecord(syncedClassId, isNextSchoolDay = false) },
                // Only the year that holds today. The lookahead row answers
                // «what is the next school day» from *now*, and a fetch of 2031
                // rewriting it would point the widget and the notifications at
                // a Monday five years out.
                //
                // It writes nothing today, and that is not a defect here: the
                // server resolves `next_school_day` at most three weeks past
                // the last lesson *in the window asked for*, and this window is
                // the school year, so those three weeks are in June and out of
                // season for every class. What answers across a gap instead is
                // `firstTeachingDayAfter` over the cached year — see
                // `TimetableDao`'s ranged deletes. The guard is kept rather
                // than the branch removed: the field is in every bundle, a
                // narrower window fills it, and the row it would write is one
                // no screen could question.
                nextSchoolDay = timetable.nextSchoolDay
                    ?.takeIf { openingYear == SchoolYear.openingYearOf(todayAtSchool()) }
                    ?.toRecord(syncedClassId, isNextSchoolDay = true),
            )
            // After the write, not before: a tag remembered for a window that
            // failed to land would make the next sync ask «changed since?» about
            // rows this device does not have.
            response.headers()["ETag"]?.takeIf { it.isNotBlank() }?.let { etag ->
                bundleTags.remember(signature, etag)
            }
            // Guarded: the sync succeeded, and a failure to tidy up afterwards
            // is not a failure to sync. The worst an unpruned cache costs is
            // rows nobody reads until the next successful prune.
            runCatching { prune(syncedClassId) }
            onDataChanged()
            SyncResult.Success
        } catch (cancellation: CancellationException) {
            // A cancelled sync is not a failed sync; let it propagate.
            throw cancellation
        } catch (http: HttpException) {
            if (http.code() == HTTP_UNAUTHORISED) {
                // Before returning, not after: the caller shows the message,
                // and by the time it does the app must already be out of a
                // class that no longer has this device in it.
                //
                // Guarded, because it writes: `signOut` clears the session
                // through DataStore, whose write side rethrows `IOException`,
                // and this runs inside a `catch` with nothing above it but the
                // bare `viewModelScope.launch` of whoever asked for a refresh.
                // A full disk would turn a handled 401 into a crash.
                runCatching { onTokenRejected() }
                SyncResult.Unauthorised
            } else {
                SyncResult.Failed("Server returned HTTP ${http.code()}")
            }
        } catch (missing: ServerAddressMissingException) {
            SyncResult.NotConfigured
        } catch (io: IOException) {
            SyncResult.Failed(io.message ?: "Network unavailable")
        } catch (unexpected: Exception) {
            // Malformed payloads and database constraint violations end up here.
            // The cache is untouched, because replaceWindow is one transaction.
            SyncResult.Failed(unexpected.message ?: unexpected::class.java.simpleName)
        }
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
     * evening on the last day of school, for *next* September–May — and since
     * `replaceAll` wipes the table before writing, the app and the widget both
     * fall to «Нет данных» for the rest of that day and stay there until the
     * device's own date rolls over.
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

        const val MIN_DAYS = 1

        /** The server rejects anything larger (`MAX_BUNDLE_DAYS`). */
        const val MAX_DAYS = 280

        const val HTTP_UNAUTHORISED = 401

        /** The whole point of sending `If-None-Match`: a body that is not sent. */
        const val HTTP_NOT_MODIFIED = 304
    }
}

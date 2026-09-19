package com.lumenpearson.lessons.core.data.repository

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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
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
) : TimetableRepository {

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
     * already the cheap one.
     */
    private suspend fun hasCachedWindow(): Boolean {
        val classId = activeClassId.first() ?: return false
        return dao.schoolClass(classId) != null
    }

    override suspend fun refresh(days: Int): SyncResult = withContext(ioDispatcher) {
        try {
            val today = todayAtSchool()
            // The school year, not a window measured from today.
            //
            // It used to be a rolling month anchored to Monday of the current
            // week — the anchor because `replaceAll` wipes the table each sync,
            // so days already past this week were being destroyed rather than
            // merely not fetched. The month was the part that was wrong: the
            // calendar draws a whole year, and every date past the window came
            // out «Нет данных», which on screen is indistinguishable from "no
            // lessons that day" and reads as a timetable that stops a month
            // after the class was made.
            //
            // Resolving the year costs the server the same handful of queries
            // as resolving a month: the weekly template is loaded once and the
            // rest is arithmetic. What it costs is payload, once per sync.
            val year = SchoolYear.boundsAt(today)
            // The Monday anchor applies only inside the year. It exists so a
            // sync mid-week does not destroy the days already past — `replaceAll`
            // wipes the table — and inside the year it can only reach back as
            // far as the Monday before 1 September, six days at most. Applied
            // in the summer it would reach back to July and ask for some 320
            // days, which is past what the server accepts, and the window would
            // silently come back clipped in April.
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val start = if (today >= year.start) minOf(year.start, monday) else year.start
            val span = (ChronoUnit.DAYS.between(start, year.endInclusive).toInt() + 1)
                .coerceIn(MIN_DAYS, MAX_DAYS)
            // `days` is honoured as a floor: a caller asking for more than the
            // year has left still gets the year, and one asking for less still
            // gets it, because a partial cache is what this is fixing.
            val window = maxOf(span, days.coerceIn(MIN_DAYS, MAX_DAYS))
            // The tag belongs to one class and one window, so the signature
            // carries both: on 1 September, and on a switch between classes,
            // the stored tag simply stops matching and the next sync asks for
            // everything, which is the direction that heals itself.
            val signature = "${activeClassId.first() ?: 0L}|$start|$window"
            var response = api.bundle(
                start = start.toString(),
                days = window,
                ifNoneMatch = bundleTags.tagFor(signature),
            )
            if (response.code() == HTTP_NOT_MODIFIED && !hasCachedWindow()) {
                // «Nothing changed» is only an answer when there is something
                // for it to be about. The tag lives in the preferences and
                // outlives every wipe of the cache — `clearSession`,
                // `removeSession`, `clearMemberships` and a destructive Room
                // migration all leave it standing — and the signature is
                // `classId|start|window`, none of which moves across a leave
                // and a re-join of the same class. So the phone could send a
                // tag the server still matched while holding nothing at all,
                // and go on reporting a successful sync over «Расписание ещё
                // не загружено» for ever: the home screen, the widget and the
                // notifications all read the same empty cache, and every pull
                // to refresh repeated the 304.
                response = api.bundle(start = start.toString(), days = window, ifNoneMatch = null)
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
                return@withContext SyncResult.Unauthorised
            }
            if (response.code() == HTTP_NOT_MODIFIED) {
                // Nothing to write: the window is byte-for-byte what is already
                // cached. The check still happened, though, and «обновлено N
                // назад» is a claim about *that* — left alone it would keep
                // growing on a phone that is syncing perfectly. The widget is
                // deliberately not poked: nothing it draws has changed, and a
                // redraw per poll is the cost this whole request was avoiding.
                activeClassId.first()?.let { dao.touchSyncedAt(it, clock.millis()) }
                // The alarm chain is told anyway, and that is not a
                // contradiction of the line above. The widget draws the data,
                // so unchanged data means nothing to redraw; the chain is a
                // *chain*, one alarm long, and what ends it is not the data
                // changing but time passing over a gap wider than the planner
                // looks ahead — a fortnight of holidays, in which nothing on
                // the server changes and therefore every poll is this branch.
                // Left silent, this is the path that watches the chain die.
                onNothingChanged()
                return@withContext SyncResult.Success
            }
            val body = response.body()
                ?: return@withContext SyncResult.Failed("Server returned HTTP ${response.code()}")
            val timetable = body.toDomain(fallbackSyncedAtEpochMillis = clock.millis())
            // The class the *server* resolved the token to, not the one this
            // device thinks is active. They are the same except in the seconds
            // around a switch, and taking the local answer there would file one
            // class's window under the other's id — which is the one mistake
            // this cache cannot survive, because both windows look plausible.
            val syncedClassId = timetable.schoolClass.id
            dao.replaceAll(
                schoolClass = timetable.schoolClass.toEntity(timetable.syncedAtEpochMillis),
                days = timetable.days.map { it.toRecord(syncedClassId, isNextSchoolDay = false) },
                nextSchoolDay = timetable.nextSchoolDay?.toRecord(
                    syncedClassId,
                    isNextSchoolDay = true,
                ),
            )
            // After the write, not before: a tag remembered for a window that
            // failed to land would make the next sync ask «changed since?» about
            // rows this device does not have.
            response.headers()["ETag"]?.takeIf { it.isNotBlank() }?.let { etag ->
                bundleTags.remember(signature, etag)
            }
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
            // The cache is untouched, because replaceAll is one transaction.
            SyncResult.Failed(unexpected.message ?: unexpected::class.java.simpleName)
        }
    }

    override suspend fun forgetClassesOtherThan(keep: Set<Long>) {
        withContext(ioDispatcher) { dao.retainOnly(keep) }
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
        const val MIN_DAYS = 1

        /** The server rejects anything larger (`MAX_BUNDLE_DAYS`). */
        const val MAX_DAYS = 280

        const val HTTP_UNAUTHORISED = 401

        /** The whole point of sending `If-None-Match`: a body that is not sent. */
        const val HTTP_NOT_MODIFIED = 304
    }
}

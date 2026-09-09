package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.TimetableDao
import com.lumenpearson.lessons.core.data.database.buildTimetable
import com.lumenpearson.lessons.core.data.database.toEntity
import com.lumenpearson.lessons.core.data.database.toRecord
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.toDomain
import com.lumenpearson.lessons.core.model.Timetable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

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
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TimetableRepository {

    /**
     * The class row is the gate: it only exists after a sync, so `null` before
     * the first one falls out of the data model instead of needing a flag.
     * `distinctUntilChanged` keeps an unrelated write - a settings change cannot
     * touch these tables, but a re-sync with identical content can - from
     * redrawing every screen.
     */
    override val timetable: Flow<Timetable?> = combine(
        dao.observeSchoolClass(),
        dao.observeDays(),
        dao.observeNextSchoolDay(),
    ) { schoolClass, days, nextSchoolDay ->
        schoolClass?.let { buildTimetable(it, days, nextSchoolDay) }
    }.distinctUntilChanged()

    override suspend fun snapshot(): Timetable? = withContext(ioDispatcher) {
        val schoolClass = dao.schoolClass() ?: return@withContext null
        buildTimetable(schoolClass, dao.days(), dao.nextSchoolDay())
    }

    override suspend fun refresh(days: Int): SyncResult = withContext(ioDispatcher) {
        val window = days.coerceIn(MIN_DAYS, MAX_DAYS)
        try {
            // "Today" is resolved on the device: the widget must be able to sync
            // a window it can actually display, even if the server's clock or
            // time zone drifts.
            val bundle = api.bundle(start = LocalDate.now(clock).toString(), days = window)
            val timetable = bundle.toDomain(fallbackSyncedAtEpochMillis = clock.millis())
            dao.replaceAll(
                schoolClass = timetable.schoolClass.toEntity(timetable.syncedAtEpochMillis),
                days = timetable.days.map { it.toRecord(isNextSchoolDay = false) },
                nextSchoolDay = timetable.nextSchoolDay?.toRecord(isNextSchoolDay = true),
            )
            SyncResult.Success
        } catch (cancellation: CancellationException) {
            // A cancelled sync is not a failed sync; let it propagate.
            throw cancellation
        } catch (http: HttpException) {
            if (http.code() == HTTP_UNAUTHORISED) {
                SyncResult.Unauthorised
            } else {
                SyncResult.Failed("Server returned HTTP ${http.code()}")
            }
        } catch (io: IOException) {
            SyncResult.Failed(io.message ?: "Network unavailable")
        } catch (unexpected: Exception) {
            // Malformed payloads and database constraint violations end up here.
            // The cache is untouched, because replaceAll is one transaction.
            SyncResult.Failed(unexpected.message ?: unexpected::class.java.simpleName)
        }
    }

    private companion object {
        const val MIN_DAYS = 1

        /** The server rejects anything larger (`MAX_BUNDLE_DAYS`). */
        const val MAX_DAYS = 31

        const val HTTP_UNAUTHORISED = 401
    }
}

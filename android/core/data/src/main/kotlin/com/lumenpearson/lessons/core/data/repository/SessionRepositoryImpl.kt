package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.TimetableDao
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * DataStore-backed memberships, plus the one call that creates them.
 *
 * It owns the cache wipe on join, on leaving a class and on sign-out: a
 * timetable that belongs to a class this device is no longer in is worse than no
 * timetable at all. Every wipe is now as narrow as the thing that caused it —
 * leaving 7«А» must not empty 9«Б», which the user would discover by switching
 * to it and finding nothing there.
 */
internal class SessionRepositoryImpl(
    private val preferences: LessonsPreferences,
    private val api: LessonsApi,
    private val dao: TimetableDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Called once the last membership and the whole cache are gone.
     *
     * Signing out has to take the armed notification alarm with it. Without this
     * the chain kept running off the last cached timetable and the phone
     * announced a lesson for a class the device had already left — and there was
     * no screen left in the app that could explain where it came from.
     */
    private val onSignedOut: () -> Unit = {},
    /**
     * Called when a different class is the one on screen — a join, a switch, or
     * leaving the class that was showing while others remain.
     *
     * The same reasoning as [onSignedOut], one step short of it: the widget and
     * the armed alarm both describe a class, and after a switch it is the wrong
     * one until something tells them. The device is still in a class, so they
     * are re-pointed rather than cleared.
     */
    private val onActiveClassChanged: () -> Unit = {},
) : SessionRepository {

    override val session: Flow<Session?> = preferences.session

    override val sessions: Flow<List<Session>> = preferences.sessions

    override suspend fun current(): Session? = preferences.currentSession()

    override suspend fun currentAll(): List<Session> = preferences.currentSessions()

    override suspend fun join(code: String, deviceName: String?): Result<Session> =
        withContext(ioDispatcher) {
            try {
                // The server upper-cases and trims join codes too; doing it here
                // as well means a pupil typing "abc123" is never told the code
                // is wrong.
                val response = api.join(
                    JoinRequestDto(
                        code = code.trim().uppercase(),
                        deviceName = deviceName?.trim()?.ifBlank { null },
                    ),
                )
                val joined = Session(
                    classId = response.classId,
                    className = response.className,
                    school = response.school?.trim()?.ifBlank { null },
                    token = response.token,
                )
                // Before the membership is stored, so the new token can never
                // show rows that predate it. Only this class's rows: the other
                // classes on the phone have nothing to do with this join.
                dao.clear(joined.classId)
                preferences.addSession(joined)
                onActiveClassChanged()
                Result.success(joined)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Classified here, because `HttpException` does not leave this
                // module: without this the join screen can see that something
                // went wrong and not which of the two refusals it was, and a
                // class that has switched to bot invites answers a perfectly
                // real code with a 403. See [JoinFailure].
                Result.failure(JoinFailure.of(failure))
            }
        }

    override suspend fun select(classId: Long) {
        withContext(ioDispatcher) {
            // Both guards before anything is written, because the notification
            // below is not free: it re-reads the cache, re-arms an alarm and
            // asks for a sync. Re-selecting the class already on screen is what
            // a list of classes does every time somebody taps the current one.
            if (preferences.currentSession()?.classId == classId) return@withContext
            if (preferences.currentSessions().none { it.classId == classId }) return@withContext
            preferences.selectSession(classId)
            onActiveClassChanged()
        }
    }

    override suspend fun leave(classId: Long) {
        withContext(ioDispatcher) {
            val wasShowing = preferences.currentSession()?.classId == classId
            // Memberships first, rows second: the screens re-point at whatever
            // class is left before its predecessor's rows go, rather than
            // drawing an empty week under a name that is about to change.
            preferences.removeSession(classId)
            dao.clear(classId)
            when {
                preferences.currentSession() == null -> onSignedOut()
                wasShowing -> onActiveClassChanged()
            }
        }
    }

    override suspend fun leaveActive() {
        withContext(ioDispatcher) {
            // Read once and handed straight on: two reads with a switch
            // possible between them would leave the class the user had just
            // moved to.
            val showing = preferences.currentSession() ?: return@withContext
            leave(showing.classId)
        }
    }

    override suspend fun signOut() {
        withContext(ioDispatcher) {
            preferences.clearSession()
            dao.clearAll()
            onSignedOut()
        }
    }
}

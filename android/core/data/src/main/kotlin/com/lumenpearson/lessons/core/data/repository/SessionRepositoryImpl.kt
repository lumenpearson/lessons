package com.lumenpearson.lessons.core.data.repository

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
 * It orders the cache wipe on join, on leaving a class and on sign-out — a
 * timetable that belongs to a class this device is no longer in is worse than no
 * timetable at all — but it no longer performs one. What «empty the cache»
 * means belongs to [TimetableCache], in one place, because it has grown twice
 * and both times this class was left describing the version before.
 *
 * Every wipe is as narrow as the thing that caused it: leaving 7«А» must not
 * empty 9«Б», which the user would discover by switching to it and finding
 * nothing there.
 */
internal class SessionRepositoryImpl(
    private val preferences: LessonsPreferences,
    private val api: LessonsApi,
    /**
     * The cached timetable, as the thing that drops it — see [TimetableCache].
     *
     * The DAO used to be here instead, and the three wipes below were three
     * calls on it. That made this class the second place that had to know what
     * «empty the cache» means, and it knew an older answer: the rows and the
     * window claims went, the `ETag` of every year they held stayed in the
     * preferences for the life of the install.
     */
    private val cache: TimetableCache,
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

    override suspend fun serverStatus(): ServerStatus = withContext(ioDispatcher) {
        // Asked before the call, not after a failure: with no address the
        // request would be sent to the placeholder host the Retrofit instance
        // is built with, fail there, and be reported as «сервер не отвечает» —
        // which is a sentence about a server nobody has named yet.
        if (preferences.baseUrlBlocking().isBlank()) return@withContext ServerStatus.NotConfigured

        try {
            val body = api.warmup()
            when (body.status) {
                "ok" -> ServerStatus.Ok(apiVersion = body.apiVersion, schema = body.schema)
                // `degraded` and `down` are both «the server answered and said
                // it is not well», and the schema pair is what says which.
                // Anything else a future server invents lands here too, with
                // its own sentence, rather than being reported as healthy.
                else -> ServerStatus.Degraded(
                    schema = body.schema,
                    expected = body.expectedSchema,
                    detail = body.detail,
                )
            }
        } catch (e: CancellationException) {
            // Rethrown, never swallowed: this runs in the settings screen's
            // scope, and turning a cancellation into «сервер не отвечает» would
            // leave a badge accusing the server of a screen that was closed.
            throw e
        } catch (_: Exception) {
            // Everything else is one answer on purpose. A reader cannot act on
            // the difference between an unresolved host, a refused connection
            // and a 502, and the exception's own message is the least readable
            // sentence on the page — it has printed a whole URL before now.
            ServerStatus.Unreachable
        }
    }

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
                    // The join is the only answer that carries it (the bundle
                    // does not), so it is kept with the membership it came
                    // with — and replaced, like the token, by a re-join.
                    diary = response.diary?.toDomain(),
                )
                // Before the membership is stored, so the new token can never
                // show rows that predate it. Only this class's rows: the other
                // classes on the phone have nothing to do with this join.
                cache.forgetClass(joined.classId)
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
            cache.forgetClass(classId)
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
            cache.forgetEverything()
            onSignedOut()
        }
    }
}

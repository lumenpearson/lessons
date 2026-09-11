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
 * DataStore-backed session, plus the one call that creates it.
 *
 * It owns the cache wipe on both join and sign-out: a timetable that belongs to
 * a class this device is no longer in is worse than no timetable at all.
 */
internal class SessionRepositoryImpl(
    private val preferences: LessonsPreferences,
    private val api: LessonsApi,
    private val dao: TimetableDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Called once the session and the cache are gone.
     *
     * Signing out has to take the armed notification alarm with it. Without this
     * the chain kept running off the last cached timetable and the phone
     * announced a lesson for a class the device had already left — and there was
     * no screen left in the app that could explain where it came from.
     */
    private val onSignedOut: () -> Unit = {},
) : SessionRepository {

    override val session: Flow<Session?> = preferences.session

    override suspend fun current(): Session? = preferences.currentSession()

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
                // Drop whatever the previous class left behind before the new
                // token can make it look current.
                dao.clearAll()
                preferences.writeSession(joined)
                Result.success(joined)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // The join screen renders the exception itself: HTTP 404 means a
                // wrong code, an IOException means a wrong or unreachable
                // address, and it is the only screen that can tell the user so.
                Result.failure(failure)
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

package com.lumenpearson.lessons.core.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Membership of a class: the one piece of identity this app has.
 *
 * Kept apart from [SettingsRepository] even though both sit in the same
 * DataStore file, because sign-out has to clear the session without touching the
 * server address the user typed in.
 */
interface SessionRepository {

    /** `null` means "not joined"; the app's root navigation switches on exactly this. */
    val session: Flow<Session?>

    /** One-shot read for workers and the widget. */
    suspend fun current(): Session?

    /**
     * Exchanges a join code for a device token and stores it.
     *
     * Returns [Result] rather than a sealed type because the join screen shows
     * the failure verbatim and has nothing to decide: a wrong code (HTTP 404), a
     * wrong address and a dead server are all just "did not work, here is why".
     */
    suspend fun join(code: String, deviceName: String?): Result<Session>

    /** Drops the token and wipes the cached timetable with it. */
    suspend fun signOut()
}

package com.lumenpearson.lessons.core.data.repository

/**
 * Value types of the data layer's public API.
 *
 * They are plain data classes rather than entities or DTOs so that the UI and
 * the widget never see a Room row or a wire field, and so that changing either
 * storage or the wire format is invisible above this line.
 */

/**
 * Proof that this device belongs to a class.
 *
 * The [token] is long-lived and read-only; there is no refresh and no password,
 * so its presence is the whole of "signed in" and its absence sends the user
 * back to the join screen.
 */
data class Session(
    val classId: Long,
    val className: String,
    val school: String?,
    val token: String,
)

/**
 * Everything the user can change.
 *
 * [baseUrl] is a setting rather than a build constant because every school hosts
 * its own server; the rest are display preferences the widget and the app share,
 * which is why they live here and not in a UI module.
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val dynamicColor: Boolean = true,
    val pitchBlack: Boolean = false,
    val showTeacher: Boolean = true,
    val widgetShowProgress: Boolean = true,
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
) {
    companion object {
        /**
         * The emulator's alias for the developer machine's localhost, i.e. a
         * `uvicorn app.main:app` running next to this checkout. There is no
         * sensible production default - the join screen asks for the address.
         */
        const val DEFAULT_BASE_URL: String = "http://10.0.2.2:8000/"

        /** Hourly is enough: a school timetable changes a few times a term. */
        const val DEFAULT_SYNC_INTERVAL_MINUTES: Int = 60

        /** WorkManager's own floor for periodic work; anything less is silently raised. */
        const val MIN_SYNC_INTERVAL_MINUTES: Int = 15
    }
}

/**
 * Outcome of a sync attempt.
 *
 * [Unauthorised] is separate from [Failed] because it is the one failure the
 * user can act on: the token was revoked server-side and the only cure is
 * joining again. Callers must not retry it.
 */
sealed interface SyncResult {

    /** The cache now holds a fresh window. */
    data object Success : SyncResult

    /** HTTP 401: the device token is gone or revoked. Send the user to the join screen. */
    data object Unauthorised : SyncResult

    /** Anything transient - no network, server down, malformed payload. Retry later. */
    data class Failed(val message: String) : SyncResult
}

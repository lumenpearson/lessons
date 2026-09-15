package com.lumenpearson.lessons.core.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Membership of a class: the one piece of identity this app has.
 *
 * A device may belong to **several** classes and shows one of them at a time.
 * That is the server's model rather than a client invention: a join code buys
 * one read-only token for one class, so a pupil who is in two is a phone
 * holding two tokens. Nothing was widened to allow it — the app was simply
 * throwing the previous token away.
 *
 * Kept apart from [SettingsRepository] even though both sit in the same
 * DataStore file, because sign-out has to clear the session without touching the
 * server address the user typed in.
 */
interface SessionRepository {

    /**
     * The class being shown, or `null` when this device is in none. The app's
     * root navigation switches on exactly this.
     */
    val session: Flow<Session?>

    /**
     * Every class this device has joined, in the order they were joined.
     *
     * Empty exactly when [session] is `null`: there is no state where the phone
     * holds memberships but shows nothing.
     */
    val sessions: Flow<List<Session>>

    /** One-shot read for workers and the widget. */
    suspend fun current(): Session?

    /** One-shot twin of [sessions]. */
    suspend fun currentAll(): List<Session>

    /**
     * Exchanges a join code for a device token and stores it.
     *
     * Adds a class rather than replacing the one already there, and shows the
     * new one. Re-entering the code of a class this phone is already in
     * replaces that membership's token in place, which is how somebody whose
     * device was revoked gets back in.
     *
     * Fails with a [JoinFailure], always. It used to fail with whatever was
     * thrown, on the grounds that the join screen had nothing to decide — and
     * then a class gained a second way to refuse a code that is perfectly real,
     * which the screen has to word differently or it sends the user to the
     * wrong person. See [JoinFailure].
     */
    suspend fun join(code: String, deviceName: String?): Result<Session>

    /**
     * Shows a class this device has already joined. A [classId] it is not in is
     * ignored, so a stale button cannot strand the app on an empty screen.
     */
    suspend fun select(classId: Long)

    /**
     * Leaves one class, keeping the others, and wipes that class's cache.
     *
     * Leaving the one on screen promotes another; leaving the last one is a
     * [signOut] and lands on the join screen.
     */
    suspend fun leave(classId: Long)

    /**
     * Leaves whichever class is on screen; does nothing when there is none.
     *
     * Exists for the one caller that cannot name a class: a `401` says the
     * bearer the request carried has been refused, and that bearer is the
     * active class's. Spelling it as [current] then [leave] at each call site
     * would be two reads with a switch possible between them.
     */
    suspend fun leaveActive()

    /** Drops every membership and wipes the whole cached timetable with them. */
    suspend fun signOut()
}

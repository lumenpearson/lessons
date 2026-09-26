package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.upstream.UpstreamSession

/**
 * Getting into a diary from this phone, in the order it has to happen.
 *
 * 1. [preflight] — before the password field is even enabled: is a server set,
 *    can it keep a session opened here, does it serve this region, and does the
 *    region take a password at all. Nothing is sent to a diary.
 * 2. [openUpstream] — the phone signs in to the diary's own server with the
 *    password. The password goes there and nowhere else: not to our server,
 *    not to a log, not to disk.
 * 3. [register] — the session the diary handed over goes to our server, which
 *    reads with it once from its own address (that read is the check), seals
 *    it, and answers with a bearer of ours. The phone then forgets the diary's
 *    session; the bearer and the [DiaryTarget] are all it keeps.
 * 4. [discard] — for a session our server refused, or one a screen backs out
 *    of: says goodbye upstream («Сетевой город»; Petersburg has no such call),
 *    so it does not linger on the diary's side for its whole idle window.
 *
 * The steps are separate because the screens need them to be: a registration
 * that failed for want of a network is retried with the session still in hand,
 * without asking for the password again — see
 * [DiarySignInProblem.retryKeepsSession]. [signIn] runs all of them for a
 * screen that holds nothing between steps.
 *
 * Every failure is a [DiarySignInProblem].
 */
interface DiarySignIn {

    /** Everything that can be checked before a password is taken. */
    suspend fun preflight(target: DiaryTarget): Result<Unit>

    /**
     * Signs in to the diary itself. The [UpstreamSession] that comes back lives
     * in memory only — never in saved state, DataStore, Room or a log — until
     * [register] or [discard] is done with it.
     */
    suspend fun openUpstream(target: DiaryTarget, password: String): Result<UpstreamSession>

    /**
     * Hands [upstream] to our server and stores what it answers — our bearer
     * and the target — in place of whatever diary this phone held before. A
     * different account empties what was kept for the previous one first, so a
     * new account never sits over the old one's rows.
     *
     * On a failure whose [DiarySignInProblem.retryKeepsSession] is `false`, the
     * session has already been discarded here; on one where it is `true`, the
     * caller may register the same session again.
     */
    suspend fun register(upstream: UpstreamSession): Result<DiaryRegistration>

    /** Ends [upstream] with the diary, best effort. Nothing once our server holds it. */
    suspend fun discard(upstream: UpstreamSession)

    /**
     * [preflight], [openUpstream] and [register] in one, discarding the session
     * on any failure after it exists: there is nobody holding it to retry.
     */
    suspend fun signIn(target: DiaryTarget, password: String): Result<DiaryRegistration>
}

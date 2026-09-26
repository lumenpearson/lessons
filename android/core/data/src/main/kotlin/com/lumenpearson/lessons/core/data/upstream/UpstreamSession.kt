package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.time.Instant

/**
 * What a sign-in on the phone ends holding: a session the diary itself issued,
 * about to be handed to our server and then forgotten.
 *
 * Deliberately awkward to keep. Not a data class, so nothing prints its fields;
 * not `@Serializable`, `Parcelable` or `java.io.Serializable`, so it cannot be
 * put in a saved-state bundle — which the system writes to disk — or in
 * DataStore or Room by accident; and [toString] is final and says nothing. The
 * secret fields are `internal`, so no screen can read them either: the only
 * thing that does is `DiarySignInImpl.register`, on its way to our server.
 *
 * It lives in memory for the seconds between the sign-in and the registration.
 * Process death in between means signing in again, which is the price of
 * never writing a live credential down.
 */
sealed class UpstreamSession {

    /** Which diary, region, school and login this session is for. Never secret. */
    abstract val target: DiaryTarget

    /** When the diary handed it over; an old one is not registered. */
    internal abstract val openedAt: Instant

    /**
     * Set once our server has accepted it. From then on the session is the
     * server's to use and to end: registering it twice would mint a second
     * row, and saying goodbye upstream would end the one the server holds —
     * so both refuse a spent session, whoever still has a reference to it.
     */
    @Volatile
    internal var spent: Boolean = false

    final override fun toString(): String = "UpstreamSession(${target.provider.wire}, <redacted>)"

    /** Petersburg's session: the `X-JWT-Token` it set, or `data.token`. */
    class Petersburg internal constructor(
        override val target: DiaryTarget,
        internal val token: String,
        override val openedAt: Instant,
    ) : UpstreamSession()

    /**
     * «Сетевой город»'s session: the `at` bearer, its session cookies, and the
     * `ver` its `logindata` gave (the server's own sign-in keeps the same one,
     * for the SecurityWarning acknowledgement and the logout).
     */
    class NetSchool internal constructor(
        override val target: DiaryTarget,
        internal val at: String,
        internal val cookies: Map<String, String>,
        internal val ver: String?,
        /** The login's `timeOut` in milliseconds, when it was a JSON integer. */
        internal val timeOut: Long?,
        override val openedAt: Instant,
    ) : UpstreamSession()
}

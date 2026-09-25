package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.upstream.UpstreamFailure
import com.lumenpearson.lessons.core.data.upstream.UpstreamNotAllowed
import com.lumenpearson.lessons.core.data.upstream.isTimeout
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * Everything that can stop a family getting into their diary on this phone, as
 * one type the screens switch on.
 *
 * A sign-in now has two counterparts — the diary's own server, which the phone
 * talks to directly, and ours, which it registers the session with — and each
 * fails in its own vocabulary: [UpstreamFailure] for the first, HTTP statuses
 * and [DiaryFailure] for the second. The sign-in form, the school search, the
 * import and Settings → Дневник all have to say one sentence about whichever it
 * was, so they switch on this and nothing else; `DiaryProblemText` in `:app` is
 * the one place that turns a case into words. Two mappings would be two
 * answers to «what does a 429 say», which is how the diary came to show «Не
 * получилось: 429» where the server had said «слишком много попыток» (#153).
 *
 * The cases that concern the diary's server carry its host, because «не
 * отвечает» about a named server is a sentence somebody can do something with
 * and «не отвечает» alone is not. The cases that concern ours carry nothing:
 * the screen already knows which server it is set to.
 *
 * No case carries the password or any part of it. [WrongPassword] carries only
 * what the diary said.
 */
sealed class DiarySignInProblem(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** What the screen offers next. */
    enum class Action {
        /** Try the same thing again; nothing typed has to change. */
        RETRY,

        /** Type the password (or the login) again. */
        RETYPE,

        /**
         * Open the diary's own site in the browser — the region lets people in
         * through Госуслуги only, which this app never signs in to.
         */
        HANDOFF,

        /** Set or change our server's address. */
        SET_SERVER,

        /** Wait before trying again; see [TooManyAttempts]. */
        WAIT,

        /** Nothing on this screen will help. */
        NONE,
    }

    abstract val action: Action

    /**
     * Whether an upstream session that was being registered when this happened
     * is still worth holding for a retry. The ones that are: our server could
     * not be reached or was busy, so nothing judged the session and the same
     * one may be registered again without the password. Everything else has
     * already said no to that session, and `DiarySignIn.register` has
     * discarded it.
     */
    open val retryKeepsSession: Boolean get() = false

    // ---- the diary's own server --------------------------------------------

    /** The diary refused the login and password. */
    data class WrongPassword(val upstreamMessage: String?) :
        DiarySignInProblem("The diary refused the credentials") {
        override val action get() = Action.RETYPE
    }

    /**
     * The region lets people in through Госуслуги only — the catalog said so
     * before anything was sent, or the diary's `logindata` did before a
     * password was. [handoffUrl] is where to send somebody instead: the
     * catalog system's `handoff_url`, which for a «Сетевой город» region is its
     * own site rather than Госуслуги's.
     */
    data class GosuslugiOnly(val handoffUrl: String?) :
        DiarySignInProblem("The diary takes no password sign-in") {
        override val action get() = Action.HANDOFF
    }

    /** The diary's server did not answer, or answered `5xx`/`429`. */
    data class ProviderUnavailable(val host: String?) :
        DiarySignInProblem("The diary is not answering") {
        override val action get() = Action.RETRY
        override val retryKeepsSession get() = true
    }

    /**
     * A `403` or a firewall page from the diary: it will not talk to this
     * phone's address. A VPN is the usual reason, so it is worth a retry once
     * that is off.
     */
    data class ProviderRefusesPhone(val host: String?) :
        DiarySignInProblem("The diary refuses this phone's address") {
        override val action get() = Action.RETRY
    }

    /**
     * The diary's certificate chains to a root the phone does not trust — the
     * Russian Trusted Root, typically. Nothing is relaxed to get past it.
     */
    data class ProviderUntrusted(val host: String?) :
        DiarySignInProblem("The phone does not trust the diary's certificate") {
        override val action get() = Action.NONE
    }

    /**
     * An answer nobody could read: the diary's to the phone (a captcha, a
     * changed sign-in page), or its answer to our server (`502`), in which case
     * [host] is `null`. An app update fixes this, not another password.
     */
    data class ProviderUnreadable(val host: String?) :
        DiarySignInProblem("The diary answered in an unreadable way") {
        override val action get() = Action.RETRY
    }

    /** The phone could not resolve or reach the diary's server at all. */
    data class ProviderOffline(val host: String?) :
        DiarySignInProblem("The diary's server cannot be reached") {
        override val action get() = Action.RETRY
    }

    // ---- either side -------------------------------------------------------

    /**
     * Nobody answered in time: the diary's server, when [host] is set, or ours
     * — a socket timeout, or a `504` from the platform's own ceiling.
     */
    data class Timeout(val host: String?) :
        DiarySignInProblem("Nobody answered in time") {
        override val action get() = Action.RETRY
        override val retryKeepsSession get() = true
    }

    // ---- our server ----------------------------------------------------------

    /** Our server could not be reached, before any session existed. */
    data object Offline : DiarySignInProblem("Could not reach the server") {
        override val action get() = Action.RETRY
    }

    /**
     * The diary let the family in, and then our server could not be reached to
     * keep the session. The session is still held: a retry registers it again,
     * without the password.
     */
    data object RegisterUnreachable :
        DiarySignInProblem("The diary let you in, but the server could not be reached") {
        override val action get() = Action.RETRY
        override val retryKeepsSession get() = true
    }

    /** No server address is set yet; nothing was sent anywhere. */
    data object ServerMissing : DiarySignInProblem("No server address is set") {
        override val action get() = Action.SET_SERVER
    }

    /**
     * The server predates sessions opened on the phone (`404` on
     * `/capabilities`), so it could not keep one. Found out before a password
     * was taken.
     */
    data object ServerTooOld : DiarySignInProblem("The server cannot keep a session opened here") {
        override val action get() = Action.SET_SERVER
    }

    /** The server's diary is switched off (no `DIARY_SECRET`). */
    data object ServerDisabled : DiarySignInProblem("The diary is switched off on this server") {
        override val action get() = Action.NONE
    }

    /**
     * The server does not keep sessions for this region, or the phone's own
     * catalog does not list it. Found out before a password was taken.
     */
    data object RegionNotServed : DiarySignInProblem("The server does not serve this region") {
        override val action get() = Action.NONE
    }

    /**
     * `409`: the diary let the family in on the phone, and then refused the
     * same session from our server's address. Retyping the password would loop
     * — it was right seconds ago — so nothing is retried automatically, and a
     * class code is the way in that still works.
     */
    data object ServerRefusedSession :
        DiarySignInProblem("The diary refused the session from the server") {
        override val action get() = Action.NONE
    }

    /**
     * `503` with `X-Diary-Unavailable: address-refused`: the diary will not
     * talk to our server at all. Not the password.
     */
    data object ServerAddressRefused :
        DiarySignInProblem("The diary refuses the server's address") {
        override val action get() = Action.NONE
    }

    /** `403` on registration: the account has no pupil the diary will show. */
    data object NoStudent : DiarySignInProblem("The diary account has no pupil") {
        override val action get() = Action.NONE
    }

    /**
     * `429`: too many attempts from here. [retryAfterSeconds] is the server's
     * `Retry-After`, or `null` when it gave none that could be read.
     */
    data class TooManyAttempts(val retryAfterSeconds: Long?) :
        DiarySignInProblem("Too many sign-in attempts") {
        override val action get() = Action.WAIT
        override val retryKeepsSession get() = true
    }

    /**
     * The diary handed over a session and it grew too old to register before
     * it was — the screen sat open, or the phone slept. It was discarded
     * rather than sent to die on the server, where it would cost an attempt.
     */
    data object SessionAgedOut : DiarySignInProblem("The diary session grew too old to keep") {
        override val action get() = Action.RETYPE
    }

    /**
     * The login is shorter than three characters once trimmed — what the
     * server refuses too. Caught before anything was sent.
     */
    data object LoginTooShort : DiarySignInProblem("The login is too short") {
        override val action get() = Action.RETYPE
    }

    /** Our session is fine, the diary's expired: the password, for the same account. */
    data object ReauthRequired : DiarySignInProblem("Diary session expired upstream") {
        override val action get() = Action.RETYPE
    }

    /** Our own session is gone: sign in again. */
    data object SignInRequired : DiarySignInProblem("Diary sign-in required") {
        override val action get() = Action.RETYPE
    }

    /**
     * Anything else — a `422` from registration included, which only a bug in
     * this app produces — kept with its cause so a bug report can carry it.
     */
    class Unexpected(val detail: String?, cause: Throwable? = null) :
        DiarySignInProblem("Unexpected diary sign-in failure" + (detail?.let { ": $it" } ?: ""), cause) {
        override val action get() = Action.RETRY
    }

    companion object {

        /**
         * Classifies whatever a sign-in, a registration, a school search or an
         * import step threw. Already a problem stays itself.
         *
         * @param host the diary server the failing call went to, for the cases
         *   that name it; `null` when the call was to our server.
         * @param registering whether the call was the registration, where a
         *   network failure means «the diary let you in and the session is
         *   still held», not «no network before anything happened».
         */
        fun of(
            failure: Throwable,
            host: String? = null,
            registering: Boolean = false,
        ): DiarySignInProblem = when (failure) {
            // Never classified: a cancelled screen is not a failure to show.
            is CancellationException -> throw failure
            is DiarySignInProblem -> failure
            is UpstreamFailure -> ofUpstream(failure, host)
            // A request to a host outside the allow-list. Every URL is built
            // from the catalog, so this is a bug here, never «нет сети».
            is UpstreamNotAllowed -> Unexpected("not an allow-listed origin", failure)
            is HttpException -> ofServer(failure.code(), failure, registering)
            is DiaryFailure -> ofDiaryFailure(failure, registering)
            is IOException -> ofTransport(failure, registering)
            else -> Unexpected(failure::class.simpleName, failure)
        }

        private fun ofUpstream(failure: UpstreamFailure, host: String?): DiarySignInProblem =
            when (failure) {
                is UpstreamFailure.BadCredentials -> WrongPassword(failure.upstreamMessage)
                is UpstreamFailure.SignInUnsupported -> GosuslugiOnly(handoffUrl = null)
                is UpstreamFailure.Unavailable ->
                    if (failure.timedOut) Timeout(host) else ProviderUnavailable(host)
                is UpstreamFailure.Offline -> ProviderOffline(host)
                is UpstreamFailure.AddressRefused -> ProviderRefusesPhone(host)
                is UpstreamFailure.Untrusted -> ProviderUntrusted(host)
                is UpstreamFailure.Unexpected -> ProviderUnreadable(host)
            }

        /** One of our server's statuses, on the registration or any diary call. */
        private fun ofServer(code: Int, failure: HttpException, registering: Boolean): DiarySignInProblem =
            when (code) {
                401 -> {
                    val reauth = failure.response()?.headers()?.get(DiaryFailure.REAUTH_HEADER)
                        ?.trim()?.equals("required", ignoreCase = true) == true
                    if (reauth) ReauthRequired else SignInRequired
                }
                // On registration, the account has no pupil; elsewhere a 403
                // is nothing this client should ever be answered.
                403 -> if (registering) NoStudent else Unexpected("HTTP 403", failure)
                // Registration's own status for «the diary refused the session
                // from our address», which is not a wrong password (K10).
                409 -> ServerRefusedSession
                // A body the server refused before asking anybody: ours to fix.
                422 -> Unexpected("HTTP 422", failure)
                429 -> TooManyAttempts(DiaryFailure.retryAfterSeconds(failure))
                502 -> ProviderUnreadable(host = null)
                503 -> when (
                    failure.response()?.headers()?.get(DiaryFailure.UNAVAILABLE_HEADER)
                        ?.trim()?.lowercase()
                ) {
                    "disabled" -> ServerDisabled
                    "address-refused" -> ServerAddressRefused
                    else -> ProviderUnavailable(host = null)
                }
                504 -> Timeout(host = null)
                else -> Unexpected("HTTP $code", failure)
            }

        private fun ofDiaryFailure(failure: DiaryFailure, registering: Boolean): DiarySignInProblem =
            when (failure) {
                DiaryFailure.SignInRequired -> SignInRequired
                DiaryFailure.ReauthRequired -> ReauthRequired
                DiaryFailure.Disabled -> ServerDisabled
                DiaryFailure.ServerAddressRefused -> ServerAddressRefused
                is DiaryFailure.Throttled -> TooManyAttempts(failure.retryAfterSeconds)
                DiaryFailure.Unavailable -> ProviderUnavailable(host = null)
                DiaryFailure.Unreadable -> ProviderUnreadable(host = null)
                is DiaryFailure.Offline -> ofTransport(failure.reason, registering)
                DiaryFailure.UnknownStudent,
                DiaryFailure.BadRange,
                DiaryFailure.Rejected,
                is DiaryFailure.Unexpected,
                -> Unexpected(failure.message, failure)
            }

        /** No answer from our server at all. */
        private fun ofTransport(failure: Throwable, registering: Boolean): DiarySignInProblem = when {
            generateSequence(failure) { it.cause }.take(8).any { it is ServerAddressMissingException } ->
                ServerMissing
            failure.isTimeout() -> Timeout(host = null)
            registering -> RegisterUnreachable
            else -> Offline
        }
    }
}

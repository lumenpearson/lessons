package com.lumenpearson.lessons.core.data.upstream

import java.io.IOException
import okhttp3.HttpUrl

/**
 * What went wrong between the phone and a diary's own server.
 *
 * The family mirrors the server's `providers/diary/errors.py` one for one where
 * the two sides meet the same answer, because the shared vectors
 * (`server/tests/vectors/diary_protocol.json`) name the failures in those words
 * and both implementations are held to them. It is kept apart from
 * `DiaryFailure`, which is about *our* server's answers: a `503` from us and a
 * `503` from a regional server mean different things to the person holding the
 * phone, and one type for both would have to be told apart by where it came
 * from, which is exactly what a type is for.
 *
 * No case carries the password or any character of it. [BadCredentials] carries
 * only what the diary said, which is how a failure message ends up on a screen
 * without a secret riding along.
 */
sealed class UpstreamFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The diary judged the login and password and refused them. */
    class BadCredentials(val upstreamMessage: String?) :
        UpstreamFailure("The diary refused the credentials")

    /**
     * The region lets people in through Госуслуги only: the catalog said so
     * before anything was sent, or `logindata` said so before a password was.
     */
    class SignInUnsupported : UpstreamFailure("The diary takes no password sign-in")

    /**
     * Nothing judged what was typed: a transport failure, a timeout, a `5xx`,
     * a `429`, or a `getdata` without a salt that can be used. Worth retrying.
     */
    class Unavailable(val reason: Throwable? = null) :
        UpstreamFailure("The diary is not answering", reason) {
        /** A timeout is its own sentence on the screen; see `DiarySignInProblem.Timeout`. */
        val timedOut: Boolean get() = reason.isTimeout()
    }

    /** The phone could not resolve or route to the host at all. */
    class Offline(val reason: Throwable) : UpstreamFailure("No network", reason)

    /**
     * A `403` or a firewall page: the diary will not talk to this phone's
     * address. HTTP-level only — see `NetSchoolSignIn` for why a refused
     * connection is not read this way on a phone.
     */
    class AddressRefused : UpstreamFailure("The diary refuses this address")

    /** A TLS chain the phone does not trust; nothing is relaxed to get past it. */
    class Untrusted(val reason: Throwable) : UpstreamFailure("Untrusted certificate", reason)

    /** An answer this port cannot read. Never a re-authentication loop. */
    class Unexpected(val detail: String? = null) :
        UpstreamFailure("The diary answered in an unreadable way" + (detail?.let { ": $it" } ?: ""))
}

/**
 * A request to a host outside the allow-list, refused before a socket opens.
 *
 * An [IOException] rather than an [UpstreamFailure] because it is thrown from
 * an OkHttp interceptor, and in OkHttp 5 anything else thrown there under
 * `enqueue` takes the dispatcher thread down with it. It is a bug in this app
 * whenever it fires — every URL is built from the catalog — so it is reported
 * as one, never as «нет сети».
 */
class UpstreamNotAllowed(url: HttpUrl) : IOException("Not an allow-listed diary origin: ${url.scheme}://${url.host}:${url.port}")

/** Whether [this] or anything it was caused by is a timeout. */
internal fun Throwable?.isTimeout(): Boolean =
    generateSequence(this) { it.cause }.take(8).any {
        it is java.net.SocketTimeoutException ||
            // OkHttp's call timeout is a bare InterruptedIOException named «timeout».
            (it is java.io.InterruptedIOException && it.message?.contains("timeout", ignoreCase = true) == true)
    }

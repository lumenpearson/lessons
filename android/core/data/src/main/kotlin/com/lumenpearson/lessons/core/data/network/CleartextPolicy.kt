package com.lumenpearson.lessons.core.data.network

import android.security.NetworkSecurityPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether this build may speak plain `http://` to a host (#202).
 *
 * The answer is the app's `network_security_config.xml`, asked through the
 * platform — the same question OkHttp asks before it opens a socket, so what
 * the screens refuse and what the network refuses cannot disagree. A release
 * build answers yes for `localhost` and `127.0.0.1` only; a debug build, whose
 * `src/debug` copy of the file overrides the release one, answers yes for
 * every host, for development against a server on the same desk.
 *
 * An interface so that the JVM tests can say which build they are: the
 * platform's class there is a stub that throws.
 */
fun interface CleartextPolicy {

    fun permits(host: String): Boolean

    companion object {
        /** The app's own answer, from its network security configuration. */
        val Platform = CleartextPolicy { host -> NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) }
    }
}

/**
 * Whether this build sends plain `http://` beyond the phone itself — a debug
 * build does, a release build does not.
 *
 * For a screen that knows only that the address is `http://`, not what it is:
 * the diary's sign-in form, whose note is a warning about who could read the
 * session in the first case and a refusal in the second. It is the base rule
 * of the same configuration [CleartextPolicy.Platform] asks host by host, so
 * the two cannot disagree except about `localhost`.
 */
fun cleartextLeavesThePhone(): Boolean = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted

/**
 * What this build will do with an address typed as the server's, asked before
 * anything is sent to it.
 */
enum class CleartextVerdict {
    /** `https://`, blank, or not an address at all — none of which this is about. */
    NOT_CLEARTEXT,

    /** Plain `http://` this build allows: a debug build, or the phone's own loopback. */
    PERMITTED,

    /** Plain `http://` a release build will not send a byte to; see [ServerNeedsHttpsException]. */
    REFUSED,
}

/**
 * The [CleartextVerdict] on [address] under [policy].
 *
 * Parsed the way [BaseUrlInterceptor] parses it, so an address this calls
 * refused is exactly one the interceptor would refuse, and one it cannot parse
 * is left to the «no address» answer that interceptor already gives.
 */
fun cleartextVerdict(address: String, policy: CleartextPolicy = CleartextPolicy.Platform): CleartextVerdict {
    val url = address.trim().toHttpUrlOrNull()
    return when {
        url == null || url.isHttps -> CleartextVerdict.NOT_CLEARTEXT
        policy.permits(url.host) -> CleartextVerdict.PERMITTED
        else -> CleartextVerdict.REFUSED
    }
}

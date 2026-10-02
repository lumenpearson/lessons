package com.lumenpearson.lessons.core.data.diagnostics

import okhttp3.Headers
import okhttp3.HttpUrl

/**
 * What of a request the network log may keep, decided in one place.
 *
 * The rule is a list of what is kept, never a list of what is dropped: a new
 * header, a new query parameter or a new path that carries a credential is
 * left out by default rather than recorded until somebody notices. So:
 *
 *  * the path, with any segment that looks like a token or a long numeric id
 *    masked — a diary's pupil id and a calendar's feed token are both path
 *    segments somewhere, and neither is anything a log needs;
 *  * the *names* of the query parameters and never their values — a date range
 *    is harmless, but the same position carries a ticket elsewhere;
 *  * a handful of response headers that say why an answer was what it was;
 *  * no request header and no body, ever. `Authorization`, `Cookie` and
 *    `Set-Cookie` are never so much as read.
 */
internal object NetworkRedaction {

    /** What a masked segment reads as. */
    const val TokenMask = "{token}"
    const val IdMask = "{id}"

    /** A segment this long with letters and digits in it is a credential until shown otherwise. */
    private const val TokenMinLength = 20

    /** Five digits and up is an id, not a version, a year or a page number. */
    private const val IdMinDigits = 5

    /**
     * The response headers that explain an answer. `X-Vercel-Id` and
     * `X-GitHub-Request-Id` are what the other side's own logs are searched by.
     */
    val KeptHeaders: List<String> = listOf(
        "Content-Type",
        "Retry-After",
        "X-Diary-Unavailable",
        "X-Diary-Reauth",
        "X-Vercel-Id",
        "X-GitHub-Request-Id",
    )

    private const val HeaderValueMax = 120
    private const val FailureMessageMax = 160

    /** `/api/v1/diary/lessons?from&to` — the path masked, the query reduced to its names. */
    fun path(url: HttpUrl): String {
        val path = "/" + url.pathSegments.joinToString("/") { segment(it) }
        val names = url.queryParameterNames
        return if (names.isEmpty()) path else path + "?" + names.sorted().joinToString("&")
    }

    fun segment(raw: String): String = when {
        raw.length >= TokenMinLength && raw.any(Char::isDigit) && raw.any(Char::isLetter) -> TokenMask
        raw.length >= IdMinDigits && raw.all(Char::isDigit) -> IdMask
        else -> raw
    }

    fun headers(headers: Headers): List<Pair<String, String>> = KeptHeaders.mapNotNull { name ->
        headers[name]?.let { name to it.take(HeaderValueMax) }
    }

    /**
     * The failure's class and its first words. An OkHttp failure names a host,
     * an address or a timeout and nothing more; the cap is for the rest.
     */
    fun failure(error: Throwable): String {
        val name = error::class.java.simpleName.ifEmpty { "Throwable" }
        val message = error.message?.take(FailureMessageMax)
        return if (message.isNullOrBlank()) name else "$name: $message"
    }
}

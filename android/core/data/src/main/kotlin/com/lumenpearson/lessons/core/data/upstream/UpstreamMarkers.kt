package com.lumenpearson.lessons.core.data.upstream

/**
 * The diaries' own words, matched against their answers.
 *
 * **The one Kotlin file in `src/main` that may hold Cyrillic outside
 * `@Preview` data and the timezone list**, and on purpose: these are not
 * strings anybody reads. They are the upstream's protocol — the heading of a
 * regional firewall's refusal page, the name «Сетевой город» gives a parent's
 * role — and they have to be matched byte for byte, in the language the
 * upstream writes them in. A string resource would be translated into English
 * by the next person who saw an untranslated name, and the match would stop
 * matching in the English build only.
 *
 * `DiaryProtocolVectorsTest` holds both constants to
 * `server/tests/vectors/diary_protocol.json`, which the server's own client is
 * held to as well, so the phone and the server cannot come to recognise a
 * refusal differently.
 */
internal object UpstreamMarkers {

    /**
     * Markers of a «we do not serve this address» page, whatever its status.
     * The order is the server's `_WAF_MARKERS`, which the vectors pin.
     */
    val WAF: List<String> = listOf("Доступ к сайту", "Request forbidden", "Access denied", "blocked")

    /**
     * Part of the name «Сетевой город» gives a parent's role — «Родитель»,
     * «Родители» — which is the role to sign in as when an account is also
     * staff and the server asks which one it is today.
     */
    const val PARENT_ROLE: String = "Родител"
}

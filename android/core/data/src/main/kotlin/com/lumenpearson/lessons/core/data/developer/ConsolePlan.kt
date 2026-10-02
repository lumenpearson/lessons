package com.lumenpearson.lessons.core.data.developer

import com.lumenpearson.lessons.core.data.upstream.UpstreamOrigin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the request console may send: our server, or one diary from the catalog. */
enum class ConsoleTarget { SERVER, DIARY }

/**
 * Which bearer the console signs a request to our server with — one the phone
 * already holds, read when the request is sent and never shown.
 *
 * There is no such choice for a diary: the phone keeps no diary credential of
 * its own (the upstream session is sealed on the server), and a header typed
 * by hand is the developer's own.
 */
enum class ConsoleAuth { NONE, DEVICE, DIARY }

/** The methods the console offers; [carriesBody] is whether the form shows a body. */
enum class ConsoleMethod(val carriesBody: Boolean) {
    GET(false),
    POST(true),
    PUT(true),
    PATCH(true),
    DELETE(true),
    HEAD(false),
}

/**
 * One request as the developer typed it.
 *
 * @property origin the diary's origin, one of [RequestConsole.diaryOrigins];
 *   ignored for [ConsoleTarget.SERVER], whose origin is the address in the
 *   settings.
 * @property path starts with `/`, query included; on our server it goes after
 *   the configured address's own path, exactly as the app's requests do.
 * @property headers one `Name: value` per line.
 */
data class ConsoleDraft(
    val target: ConsoleTarget = ConsoleTarget.SERVER,
    val origin: String? = null,
    val method: ConsoleMethod = ConsoleMethod.GET,
    val path: String = "/api/v1/health",
    val headers: String = "",
    val body: String = "",
    val auth: ConsoleAuth = ConsoleAuth.NONE,
)

/**
 * Starting points: the two unsigned reads every deployment answers, and one
 * read under each bearer. The headers already typed are kept when one is picked.
 */
val ConsolePresets: List<ConsoleDraft> = listOf(
    ConsoleDraft(path = "/api/v1/health"),
    ConsoleDraft(path = "/api/v1/warmup"),
    ConsoleDraft(path = "/api/v1/me", auth = ConsoleAuth.DEVICE),
    ConsoleDraft(path = "/api/v1/now", auth = ConsoleAuth.DEVICE),
    ConsoleDraft(path = "/api/v1/diary/capabilities", auth = ConsoleAuth.DIARY),
    ConsoleDraft(path = "/api/v1/diary/students", auth = ConsoleAuth.DIARY),
)

/** Why the console would not send a draft. The page words each one. */
enum class ConsoleRefusal {
    /** No server address in the settings, or not an address. */
    NO_SERVER,

    /** `http://` to a host this build sends https only to (#202). */
    SERVER_NEEDS_HTTPS,

    /** A diary was chosen and no origin with it. */
    NO_ORIGIN,

    /** The origin is not one the catalog allow-lists. */
    ORIGIN_NOT_ALLOWED,

    /** The path does not start with a single `/`, or carries spaces or control characters. */
    BAD_PATH,

    /** The path, once resolved, names another host, port or scheme. */
    LEAVES_ORIGIN,

    /** A header line is not `Name: value`, or there are too many. */
    BAD_HEADER,

    /** `Host`, `Content-Length`, `Transfer-Encoding` or `Connection`: the client's to write. */
    FORBIDDEN_HEADER,

    /** An `Authorization` line typed by hand as well as a bearer chosen. */
    AUTH_TWICE,

    /** A bearer chosen for a diary — the bearers are our server's. */
    AUTH_OFF_SERVER,

    /** The bearer chosen is not on this phone. */
    NO_TOKEN,

    /** A body under a method that sends none. */
    BODY_NOT_ALLOWED,
}

/**
 * What the console will send, or why it will not. A [Ready] plan never holds a
 * bearer: the sender adds it at the last moment, so nothing that keeps a plan —
 * a screen's state, a log — can keep the secret too.
 */
sealed interface ConsolePlan {
    data class Ready(
        val url: HttpUrl,
        val method: ConsoleMethod,
        val headers: List<Pair<String, String>>,
        val body: String?,
        val auth: ConsoleAuth,
    ) : ConsolePlan

    data class Refused(val reason: ConsoleRefusal, val detail: String? = null) : ConsolePlan
}

/** What the planner needs to know about the phone, and nothing it does not. */
internal data class ConsoleContext(
    val serverAddress: String?,
    val serverCleartextPermitted: (String) -> Boolean,
    val diaryOrigins: Set<UpstreamOrigin>,
    val hasDeviceToken: Boolean,
    val hasDiaryToken: Boolean,
)

private val HeaderName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

/** The client writes these from the request itself; a typed one would contradict it. */
private val ClientHeaders = setOf("host", "content-length", "transfer-encoding", "connection")

private const val MaxHeaders = 50
private const val MaxPathLength = 2_048

/**
 * The console's one rule, as a pure function: a request leaves only for our
 * server or a diary the catalog allow-lists, and only to the origin it names.
 *
 * The check is made on the URL as OkHttp resolves it, not on the text typed:
 * `//elsewhere.example/` or a path full of `..` is caught by comparing the
 * origin the request would really go to, which no spelling can talk around.
 * The client the console sends through refuses any other origin a second time
 * (see [RequestConsole]), so a mistake here is a refused request, not a leak.
 */
internal fun planConsole(draft: ConsoleDraft, context: ConsoleContext): ConsolePlan =
    when (val aim = aimOf(draft, context)) {
        is Aim.Refused -> aim.refusal
        is Aim.At -> planAt(aim.base, draft, context)
    }

@Suppress("ReturnCount") // One refusal per rule, in the order the form reads.
private fun planAt(base: HttpUrl, draft: ConsoleDraft, context: ConsoleContext): ConsolePlan {
    val url = resolvePath(base, draft.path) ?: return ConsolePlan.Refused(ConsoleRefusal.BAD_PATH)
    if (UpstreamOrigin.of(url) != UpstreamOrigin.of(base)) {
        return ConsolePlan.Refused(ConsoleRefusal.LEAVES_ORIGIN, url.host)
    }
    val headers = parseHeaders(draft.headers) ?: return ConsolePlan.Refused(ConsoleRefusal.BAD_HEADER)
    return headerRefusal(draft, headers, context)
        ?: bodyRefusal(draft)
        ?: ConsolePlan.Ready(url, draft.method, headers, draft.body.takeIf { it.isNotBlank() }, draft.auth)
}

/** The origin a draft is aimed at, or why it cannot be aimed there. */
private sealed interface Aim {
    class At(val base: HttpUrl) : Aim
    class Refused(val refusal: ConsolePlan.Refused) : Aim
}

private fun refuse(reason: ConsoleRefusal, detail: String? = null): Aim =
    Aim.Refused(ConsolePlan.Refused(reason, detail))

private fun aimOf(draft: ConsoleDraft, context: ConsoleContext): Aim = when (draft.target) {
    ConsoleTarget.SERVER -> {
        val configured = context.serverAddress?.trim()?.toHttpUrlOrNull()
        when {
            configured == null -> refuse(ConsoleRefusal.NO_SERVER)
            !configured.isHttps && !context.serverCleartextPermitted(configured.host) ->
                refuse(ConsoleRefusal.SERVER_NEEDS_HTTPS, configured.host)
            else -> Aim.At(configured)
        }
    }
    ConsoleTarget.DIARY -> {
        val origin = draft.origin?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
        when {
            origin == null -> refuse(ConsoleRefusal.NO_ORIGIN)
            UpstreamOrigin.of(origin) !in context.diaryOrigins -> refuse(ConsoleRefusal.ORIGIN_NOT_ALLOWED, origin.host)
            draft.auth != ConsoleAuth.NONE -> refuse(ConsoleRefusal.AUTH_OFF_SERVER)
            else -> Aim.At(origin)
        }
    }
}

private fun headerRefusal(
    draft: ConsoleDraft,
    headers: List<Pair<String, String>>,
    context: ConsoleContext,
): ConsolePlan.Refused? {
    val clientOwned = headers.firstOrNull { (name, _) -> name.lowercase() in ClientHeaders }
    val tokenMissing = when (draft.auth) {
        ConsoleAuth.NONE -> false
        ConsoleAuth.DEVICE -> !context.hasDeviceToken
        ConsoleAuth.DIARY -> !context.hasDiaryToken
    }
    return when {
        clientOwned != null -> ConsolePlan.Refused(ConsoleRefusal.FORBIDDEN_HEADER, clientOwned.first)
        draft.auth != ConsoleAuth.NONE && headers.any { (name, _) -> name.equals("Authorization", true) } ->
            ConsolePlan.Refused(ConsoleRefusal.AUTH_TWICE)
        tokenMissing -> ConsolePlan.Refused(ConsoleRefusal.NO_TOKEN)
        else -> null
    }
}

private fun bodyRefusal(draft: ConsoleDraft): ConsolePlan.Refused? =
    ConsolePlan.Refused(ConsoleRefusal.BODY_NOT_ALLOWED).takeIf { draft.body.isNotBlank() && !draft.method.carriesBody }

/**
 * [path] under [base]'s origin and its own path prefix — `/lessons/` on a
 * server behind a proxy — as `BaseUrlInterceptor` does for every app request.
 */
private fun resolvePath(base: HttpUrl, path: String): HttpUrl? {
    val typed = path.trim()
    val wellFormed = typed.startsWith("/") && !typed.startsWith("//") &&
        typed.length <= MaxPathLength && typed.none { it.isWhitespace() || it.isISOControl() }
    if (!wellFormed) return null
    val prefix = base.encodedPath.trimEnd('/')
    // Built by OkHttp rather than by string, so an IPv6 host keeps its brackets.
    val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    return "$origin$prefix$typed".toHttpUrlOrNull()
}

/** `null` when any line is not a header; blank lines are skipped. */
internal fun parseHeaders(text: String): List<Pair<String, String>>? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val parsed = lines.map(::headerOf)
    return if (lines.size > MaxHeaders || parsed.any { it == null }) null else parsed.filterNotNull()
}

private fun headerOf(line: String): Pair<String, String>? {
    val colon = line.indexOf(':')
    val name = if (colon > 0) line.substring(0, colon).trim() else ""
    val value = if (colon > 0) line.substring(colon + 1).trim() else ""
    val valid = HeaderName.matches(name) && value.none { it.isISOControl() && it != '\t' }
    return if (valid) name to value else null
}

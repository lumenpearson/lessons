package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * `server/tests/vectors/diary_protocol.json`, read in place from the server's
 * tests — `core/data/build.gradle.kts` puts that folder on the test classpath —
 * so there is one file of known answers and not two that agree until somebody
 * edits one.
 */
internal object Vectors {
    val root: JsonObject by lazy {
        val stream = Vectors::class.java.classLoader!!.getResourceAsStream("diary_protocol.json")
            ?: error("diary_protocol.json is not on the test classpath; see core/data/build.gradle.kts")
        Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    }

    val petersburg: JsonObject get() = root.getValue("petersburg").jsonObject
    val netschool: JsonObject get() = root.getValue("netschool").jsonObject
    val flow: JsonObject get() = netschool.getValue("flow").jsonObject

    fun cases(parent: JsonObject, key: String): List<JsonObject> =
        parent.getValue(key).jsonArray.map { it.jsonObject }
}

/** The vectors' name for a failure, most specific first — as the server's `_kind`. */
internal fun kindOf(failure: Throwable?): String = when (failure) {
    null -> "ok"
    is UpstreamFailure.AddressRefused -> "address_refused"
    is UpstreamFailure.Unavailable -> "unavailable"
    is UpstreamFailure.BadCredentials -> "bad_credentials"
    is UpstreamFailure.SignInUnsupported -> "sign_in_unsupported"
    is UpstreamFailure.Unexpected -> "unexpected"
    else -> failure::class.simpleName ?: "?"
}

/** Shaped like the `X-JWT-Token` Petersburg issues, which is what the server accepts. */
internal const val PETERSBURG_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOjF9.c2lnbmF0dXJl"

/** The `NSSESSIONID` [NetSchoolScript]'s `logindata` sets unless a test says otherwise. */
internal const val SCRIPT_SESSION_COOKIE = "nss-cookie-value"

/** A clock a test moves by hand. */
internal class MutableClock(var now: Instant = Instant.parse("2026-09-25T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

internal fun jsonAnswer(status: Int, body: JsonElement, vararg headers: Pair<String, String>): MockResponse =
    MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", "application/json")
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .body(body.toString())
        .build()

internal fun rawAnswer(status: Int, contentType: String, body: String, cookies: List<String> = emptyList()): MockResponse =
    MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", contentType)
        .apply { cookies.forEach { addHeader("Set-Cookie", it) } }
        .body(body)
        .build()

/** The provider client, allowed to talk to [server] and nothing else. */
internal fun clientFor(server: MockWebServer): OkHttpClient =
    UpstreamHttp.client { setOf(UpstreamOrigin.of(server.url("/"))) }

/** The origin of [server] as the catalog would give it: scheme, host and port. */
internal fun originOf(server: MockWebServer): HttpUrl = server.url("/")

internal fun regionAt(server: MockWebServer, key: String = "zabaikalsky", password: Boolean = true) =
    NetSchoolRegion(
        key = key,
        origin = originOf(server),
        password = password,
        zone = "Asia/Chita",
        handoffUrl = "https://region.zabedu.ru",
    )

internal fun netschoolTarget(login: String = "parent", schoolId: Long = 42, region: String = "zabaikalsky") =
    DiaryTarget.netschool(region = region, schoolId = schoolId, schoolName = null, login = login, zone = "Asia/Chita")

/** A directory of exactly the regions and Petersburg origin a test hands it. */
internal class FakeUpstreamDirectory(
    override val petersburg: HttpUrl?,
    private val regions: List<NetSchoolRegion> = emptyList(),
) : UpstreamDirectory {
    override fun netschool(regionKey: String?): NetSchoolRegion? = regions.firstOrNull { it.key == regionKey }

    override fun allowedOrigins(): Set<UpstreamOrigin> = buildSet {
        petersburg?.let { add(UpstreamOrigin.of(it)) }
        regions.forEach { add(UpstreamOrigin.of(it.origin)) }
    }
}

/**
 * «Сетевой город» as a script: `logindata`, `getdata` and `login` answer what
 * the test says, falling back to the vectors' own flow, and every request is
 * recorded in the order it arrived.
 */
internal class NetSchoolScript(
    var logindata: () -> MockResponse = {
        jsonAnswer(200, Vectors.flow.getValue("logindata"), "Set-Cookie" to "NSSESSIONID=$SCRIPT_SESSION_COOKIE; Path=/")
    },
    var getdata: () -> MockResponse = { jsonAnswer(200, Vectors.flow.getValue("getdata")) },
    val logins: ArrayDeque<() -> MockResponse> = ArrayDeque(),
    var search: () -> MockResponse = { jsonAnswer(200, JsonArray(emptyList())) },
    var petersburg: () -> MockResponse = {
        rawAnswer(200, "application/json", """{"data":{"token":"$PETERSBURG_JWT"}}""")
    },
) : Dispatcher() {

    val seen: MutableList<RecordedRequest> = java.util.Collections.synchronizedList(mutableListOf())

    override fun dispatch(request: RecordedRequest): MockResponse {
        seen += request
        return when (request.url.encodedPath) {
            NetSchoolSignIn.LOGINDATA_PATH -> logindata()
            NetSchoolSignIn.GETDATA_PATH -> getdata()
            NetSchoolSignIn.LOGIN_PATH ->
                logins.removeFirstOrNull()?.invoke()
                    ?: jsonAnswer(200, Json.parseToJsonElement("""{"at":"after-role"}"""))
            NetSchoolSignIn.LOGOUT_PATH -> jsonAnswer(200, JsonObject(emptyMap()))
            NetSchoolSchoolSearch.PATH -> search()
            PetersburgSignIn.LOGIN_PATH -> petersburg()
            else -> MockResponse.Builder().code(404).build()
        }
    }

    fun to(path: String): List<RecordedRequest> = seen.filter { it.url.encodedPath == path }
}

/**
 * A form body as the fields it carries, in order, decoded — never as bytes:
 * OkHttp and httpx write the same form differently (`+` against `%20`, `~`).
 */
internal fun RecordedRequest.form(): LinkedHashMap<String, String> {
    val text = body?.utf8().orEmpty()
    val out = LinkedHashMap<String, String>()
    if (text.isEmpty()) return out
    for (pair in text.split('&')) {
        val (name, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        out[URLDecoder.decode(name, Charsets.UTF_8)] = URLDecoder.decode(value, Charsets.UTF_8)
    }
    return out
}

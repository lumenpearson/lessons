package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.time.Clock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Petersburg's sign-in, on the phone: the password goes to
 * `dnevnik2.petersburgedu.ru` and nowhere else.
 *
 * A port of the server's `PetersburgClient.login` (`petersburg/client.py`),
 * including its order of questions — `5xx` before `400/401/403`, the cookie
 * before the body — and held to it by the `petersburg.login_cases` of the
 * shared vectors. One reading differs from the server's other calls on
 * purpose, as it does on the server: a `200` that is not JSON is an unreadable
 * answer, never «your session ended». Nobody is signed in yet, and reading it
 * as a session problem sent the person back to the form they were already
 * looking at, in a loop with no way out.
 */
internal class PetersburgSignIn(
    private val client: OkHttpClient,
    private val origin: HttpUrl,
    private val clock: Clock = Clock.systemUTC(),
) {

    suspend fun signIn(target: DiaryTarget, password: String): UpstreamSession.Petersburg {
        val login = target.login.trim()
        val request = Request.Builder()
            .url(origin.newBuilder().encodedPath(LOGIN_PATH).build())
            .header("Accept", "application/json")
            .header("Accept-Charset", "UTF-8")
            .post(body(login, password).toString().toRequestBody(JSON))
            .build()
        val answer = UpstreamHttp.fetch(client, request)
        val token = read(answer, clock.millis())
        return UpstreamSession.Petersburg(
            target = target.copy(login = login),
            token = token,
            openedAt = clock.instant(),
        )
    }

    internal companion object {
        const val LOGIN_PATH = "/api/user/auth/login"
        const val SESSION_COOKIE = "X-JWT-Token"

        private val JSON = "application/json".toMediaType()

        /**
         * Their field names, including the two that look like leftovers:
         * sending fewer gets a 400. `activation_code` is an explicit `null`,
         * which the app's usual `Json` (nulls dropped) would not write.
         */
        fun body(login: String, password: String): JsonObject = buildJsonObject {
            put("type", "email")
            put("login", login)
            put("activation_code", JsonNull)
            put("password", password)
            put("_isEmpty", false)
        }

        /** The session this answer issued, or the failure it means. */
        fun read(answer: UpstreamAnswer, nowMillis: Long): String {
            if (answer.code >= 500) throw UpstreamFailure.Unavailable()
            if (answer.code == 400 || answer.code == 401 || answer.code == 403) {
                throw UpstreamFailure.BadCredentials(null)
            }
            val body = UpstreamJson.parse(answer.body) as? JsonObject
                // Not JSON at any status, including a 200 of HTML and a 3xx that
                // was not followed; or JSON that is not an object.
                ?: throw UpstreamFailure.Unexpected()
            val data = body["data"]
            val fields: JsonObject? = when (data) {
                is JsonObject -> data
                null, JsonNull -> {
                    // Some errors answer 200 with only a message.
                    val message = UpstreamJson.string(body["message"])?.trim()?.takeIf { it.isNotEmpty() }
                        ?: firstError(body)
                    throw UpstreamFailure.Unexpected(message)
                }
                // A list or a scalar: nothing to read a token from, but the
                // cookie may still carry one, as on the server.
                else -> null
            }
            // The cookie first: the two have been seen to differ, and the
            // cookie is the one later calls accept.
            val token = UpstreamHttp.sessionCookie(answer.url, answer.headers, SESSION_COOKIE, nowMillis)
                ?: fields?.let { UpstreamJson.string(it["token"])?.trim()?.takeIf(String::isNotEmpty) }
            return token ?: throw UpstreamFailure.Unexpected()
        }

        private fun firstError(body: JsonObject): String? {
            val errors = body["errors"] as? kotlinx.serialization.json.JsonArray ?: return null
            val first = errors.firstOrNull() ?: return null
            return when (first) {
                is JsonObject -> UpstreamJson.string(first["message"])?.trim()?.takeIf { it.isNotEmpty() }
                else -> UpstreamJson.string(first)
            }
        }
    }
}

package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.repository.DiaryLogin
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * «Сетевой город»'s password sign-in, on the phone: the password is hashed here
 * and the pair goes to the region's own server, never to ours.
 *
 * A port of the server's `NetSchoolClient.login` (`netschool/client.py`) —
 * `logindata`, then `getdata` for a one-shot salt, then the hashed `login`,
 * rerun once with a fresh salt when the account is asked which role it is
 * signing in as — held to it by the shared vectors, case for case. It ends at
 * the accepted login: the phone runs no bootstrap and makes no read with the
 * session. It hands it to our server, which does both from its own address,
 * and then forgets it.
 *
 * **One deliberate difference from the server.** The server reads a transport
 * failure whose text says `reset`, `refused` or `denied` as the region refusing
 * its address, a rule written for a datacentre dropped at a regional firewall.
 * On a phone, a refused connection is far more often the phone's own network or
 * a captive portal, so here it is [UpstreamFailure.Unavailable]. The HTTP-level
 * refusal rule — a `403`, or a firewall page — is kept exactly, and the vectors
 * pin it.
 *
 * Nothing secret ever goes into a URL: `at`, the cookies, `pw` and `pw2` travel
 * in headers and form bodies only.
 */
internal class NetSchoolSignIn(
    private val client: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** The session being assembled: what `_absorb_cookies` and `self.session` are on the server. */
    private class Draft(val origin: HttpUrl) {
        val cookies = LinkedHashMap<String, String>()
        var ver: String? = null
    }

    /**
     * @throws UpstreamFailure for every answer that is not a session; see the
     *   class comment for which is which.
     */
    suspend fun signIn(region: NetSchoolRegion, target: DiaryTarget, password: String): UpstreamSession.NetSchool {
        // Before any request: a region that takes Госуслуги only is sent nothing.
        if (!region.password) throw UpstreamFailure.SignInUnsupported()
        val schoolId = target.schoolId ?: throw UpstreamFailure.Unexpected("no school")
        // The login the server would have cleaned before any upstream call,
        // so a pasted bidi mark is not a wrong password.
        val login = DiaryLogin.clean(target.login) ?: throw DiarySignInProblem.LoginTooShort
        val draft = Draft(region.origin)

        loginAllowed(draft)
        val login1 = passwordLogin(draft, login, password, schoolId, rolegroup = null)
        val accepted = when (login1) {
            is Outcome.Accepted -> login1
            is Outcome.NeedsRole -> {
                // The server wants a role chosen and names none this code can
                // read: asking again without one would ask the same question.
                val role = login1.rolegroup ?: throw UpstreamFailure.Unexpected("role")
                when (val login2 = passwordLogin(draft, login, password, schoolId, rolegroup = role)) {
                    is Outcome.Accepted -> login2
                    // `tried_role` makes a second role question a refusal in
                    // `consumeLogin`, so this branch cannot be reached; it is
                    // here so the `when` needs no cast.
                    is Outcome.NeedsRole -> throw UpstreamFailure.BadCredentials(null)
                }
            }
        }
        return UpstreamSession.NetSchool(
            target = target.copy(login = login),
            at = accepted.at,
            cookies = draft.cookies.toMap(),
            ver = draft.ver,
            timeOut = accepted.timeOut,
            openedAt = clock.instant(),
        )
    }

    /**
     * Says goodbye upstream, best effort — for a session our server refused,
     * so it does not linger on the diary's side for its whole idle window.
     * Never after a successful registration: that would end the session our
     * server now holds.
     */
    suspend fun logout(session: UpstreamSession.NetSchool, region: NetSchoolRegion) {
        try {
            val form = FormBody.Builder()
                .add("at", session.at)
                .add("ver", session.ver.orEmpty())
                .build()
            UpstreamHttp.fetch(
                client,
                authed(Request.Builder(), session.at, session.cookies)
                    .url(region.origin.newBuilder().encodedPath(LOGOUT_PATH).build())
                    .post(form)
                    .build(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // We are leaving anyway.
        }
    }

    // ---- the steps --------------------------------------------------------

    private suspend fun loginAllowed(draft: Draft) {
        val answer = send(draft, LOGINDATA_PATH, body = null, auth = false)
        val data = decode(answer) as? JsonObject ?: throw UpstreamFailure.Unexpected()
        absorb(draft, answer)
        // Absent means allowed, as the server's `data.get("schoolLogin", True)`.
        if ("schoolLogin" in data && !UpstreamJson.truthy(data["schoolLogin"])) {
            throw UpstreamFailure.SignInUnsupported()
        }
        // Kept only when the server gave one, never as the word "null".
        UpstreamJson.scalarText(data["cacheVer"]).takeIf { it.isNotEmpty() }?.let { ver ->
            if (draft.ver == null) draft.ver = ver
        }
    }

    internal sealed interface Outcome {
        class Accepted(val at: String, val timeOut: Long?) : Outcome
        class NeedsRole(val rolegroup: String?) : Outcome
    }

    private suspend fun passwordLogin(
        draft: Draft,
        login: String,
        password: String,
        schoolId: Long,
        rolegroup: String?,
    ): Outcome {
        // An empty body with no media type, as httpx sends `data={}`; the salt
        // is one-shot and tied to the cookie `logindata` set, so every attempt
        // — the role retry included — asks for a fresh one.
        val getdata = send(draft, GETDATA_PATH, body = ByteArray(0).toRequestBody(null), auth = true)
        absorb(draft, getdata)
        val payload = decode(getdata) as? JsonObject ?: JsonObject(emptyMap())
        val salt = UpstreamJson.scalarText(payload["salt"])
        val lt = UpstreamJson.scalarText(payload["lt"])
        val ver = UpstreamJson.scalarText(payload["ver"])
        // Nothing has judged the password yet: this is the diary's failure.
        if (salt.isEmpty() || lt.isEmpty()) throw UpstreamFailure.Unavailable()
        if (!salt.all { it.code < 0x80 }) throw UpstreamFailure.Unavailable()
        val (pw, pw2) = NetSchoolPassword.hash(salt, password)
            ?: throw UpstreamFailure.BadCredentials(null)

        val form = FormBody.Builder()
            .add("loginType", "1")
            .add("scid", schoolId.toString())
            .add("un", login)
            .add("pw", pw)
            .add("pw2", pw2)
            .add("lt", lt)
            .add("ver", ver)
            .apply { if (rolegroup != null) add("rolegroup", rolegroup) }
            .build()
        val answer = send(draft, LOGIN_PATH, body = form, auth = true)
        absorb(draft, answer)
        return consumeLogin(answer, triedRole = rolegroup != null)
    }

    // ---- plumbing ---------------------------------------------------------

    private suspend fun send(draft: Draft, path: String, body: RequestBody?, auth: Boolean): UpstreamAnswer {
        val builder = Request.Builder().url(draft.origin.newBuilder().encodedPath(path).build())
        if (auth) authed(builder, at = null, cookies = draft.cookies)
        builder.header("Accept", ACCEPT).header("X-Requested-With", "XMLHttpRequest")
        if (body == null) builder.get() else builder.post(body)
        val answer = UpstreamHttp.fetch(client, builder.build())
        if (looksRefused(answer)) throw UpstreamFailure.AddressRefused()
        return answer
    }

    private fun authed(builder: Request.Builder, at: String?, cookies: Map<String, String>): Request.Builder {
        builder.header("Accept", ACCEPT).header("X-Requested-With", "XMLHttpRequest")
        if (!at.isNullOrEmpty()) builder.header("at", at)
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        return builder
    }

    private fun absorb(draft: Draft, answer: UpstreamAnswer) {
        val now = clock.millis()
        for (name in SESSION_COOKIES) {
            val value = UpstreamHttp.sessionCookie(answer.url, answer.headers, name, now) ?: continue
            // Only a value that can be sent back as one cookie; anything else
            // would smuggle a second one onto every later call.
            if (UpstreamValues.cookieValueOk(value)) draft.cookies[name] = value
        }
    }

    internal companion object {
        const val LOGINDATA_PATH = "/webapi/logindata"
        const val GETDATA_PATH = "/webapi/auth/getdata"
        const val LOGIN_PATH = "/webapi/login"
        const val LOGOUT_PATH = "/webapi/auth/logout"

        /** The session's cookies worth carrying: the session, and the ESRN security module's. */
        val SESSION_COOKIES: List<String> = listOf("NSSESSIONID", "ESRNSec")

        const val ACCEPT = "application/json, text/plain, */*"
        private const val ROLE_QUESTION = "choose-session-role"
        private const val MESSAGE_LIMIT = 200

        /**
         * The server's `_looks_refused`: a `403`, or a page that is not JSON and
         * carries a firewall's words in its first 2000 characters.
         */
        fun looksRefused(answer: UpstreamAnswer): Boolean {
            if (answer.code == 403) return true
            val type = answer.contentType
            if ("html" in type || "json" !in type) {
                val head = answer.body.take(2000)
                return UpstreamMarkers.WAF.any { it in head }
            }
            return false
        }

        /** The server's `_decode`: a JSON body, or the failure its absence means. */
        fun decode(answer: UpstreamAnswer): JsonElement {
            if (answer.code >= 500 || answer.code == 429) throw UpstreamFailure.Unavailable()
            return UpstreamJson.parse(answer.body) ?: throw UpstreamFailure.Unexpected()
        }

        /** The server's `_consume_login`, answer for answer. */
        fun consumeLogin(answer: UpstreamAnswer, triedRole: Boolean): Outcome {
            val status = answer.code
            if (status == 409) throw UpstreamFailure.BadCredentials(loginMessage(UpstreamJson.parse(answer.body)))
            if (status == 429 || status >= 500) throw UpstreamFailure.Unavailable()
            val body = UpstreamJson.parse(answer.body) as? JsonObject ?: throw UpstreamFailure.Unexpected()
            val entry = UpstreamJson.string(body["entryPoint"])
            if (entry != null && ROLE_QUESTION in entry && !triedRole) {
                return Outcome.NeedsRole(pickParentRole(body))
            }
            val at = UpstreamJson.string(body["at"])
            if (at.isNullOrEmpty()) throw UpstreamFailure.BadCredentials(loginMessage(body))
            // Accepted, but not a value that can go in a header whole: a shape
            // this code does not know, not a wrong password.
            if (!UpstreamValues.headerValueOk(at)) throw UpstreamFailure.Unexpected()
            val timeOut = UpstreamJson.integer(body["timeOut"])
                ?.takeIf { it.signum() != 0 && it.bitLength() < 63 }
                ?.toLong()
            return Outcome.Accepted(at, timeOut)
        }

        /** `message`, else `errorMessage`, if either is non-blank text; cut to 200 characters. */
        fun loginMessage(source: JsonElement?): String? {
            val body = source as? JsonObject ?: return null
            val picked = body["message"]?.takeIf(UpstreamJson::truthy) ?: body["errorMessage"]
            val text = UpstreamJson.string(picked)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return text.cutCodePoints(MESSAGE_LIMIT)
        }

        /**
         * The role to sign in as: the first parent's, else the first role's —
         * the server's `_pick_parent_role`. A role is `entry.role` or the entry
         * itself, and counts only with an id that is a JSON string or integer.
         */
        fun pickParentRole(body: JsonObject): String? {
            val info = body["accountInfo"]
            var roles: JsonElement? = (info as? JsonObject)?.get("userRoles")
            if (!UpstreamJson.truthy(roles)) roles = body["userRoles"]
            val list = roles as? JsonArray ?: return null
            val usable = list.mapNotNull { entry ->
                val holder = entry as? JsonObject ?: return@mapNotNull null
                val role = (if ("role" in holder) holder["role"] else holder) as? JsonObject
                    ?: return@mapNotNull null
                val id = UpstreamJson.scalarText(role["id"]).takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                id to UpstreamJson.string(role["name"]).orEmpty()
            }
            return usable.firstOrNull { (_, name) -> UpstreamMarkers.PARENT_ROLE in name }?.first
                ?: usable.firstOrNull()?.first
        }

        /** Python's `text[:limit]`: whole code points, never half a surrogate pair. */
        private fun String.cutCodePoints(limit: Int): String {
            if (codePointCount(0, length) <= limit) return this
            return substring(0, offsetByCodePoints(0, limit))
        }
    }
}

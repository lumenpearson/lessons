package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.DiaryApi
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionResponseDto
import com.lumenpearson.lessons.core.data.network.dto.NetSchoolCookiesDto
import com.lumenpearson.lessons.core.data.network.dto.NetSchoolCredentialDto
import com.lumenpearson.lessons.core.data.network.dto.PetersburgCredentialDto
import com.lumenpearson.lessons.core.data.upstream.NetSchoolRegion
import com.lumenpearson.lessons.core.data.upstream.NetSchoolSignIn
import com.lumenpearson.lessons.core.data.upstream.PetersburgSignIn
import com.lumenpearson.lessons.core.data.upstream.UpstreamDirectory
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
import com.lumenpearson.lessons.core.data.upstream.UpstreamValues
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.HttpException

/**
 * [DiarySignIn] over the diaries' own servers and ours.
 *
 * @param directory the bundled catalog's allow-list, the only source of a host
 *   the phone signs in to. A function because building it reads a few hundred
 *   kilobytes of JSON, which happens on first use, on the IO dispatcher, rather
 *   than on the main thread in the container's constructor.
 * @param client the provider client (`UpstreamHttp.client`), which carries
 *   none of our server's interceptors; built on first use for the same reason.
 * @param forgetLocal empties whatever this phone keeps about a diary account
 *   beyond the store — the offline diary, once there is one. Called when a
 *   different account signs in, before its session is written.
 */
internal class DiarySignInImpl(
    private val api: DiaryApi,
    private val store: DiarySessionStore,
    private val directory: () -> UpstreamDirectory,
    private val client: () -> OkHttpClient,
    private val forgetLocal: suspend () -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiarySignIn {

    /** Where a target's sign-in goes, resolved from the catalog and nothing else. */
    private sealed interface Endpoint {
        val origin: HttpUrl

        class Petersburg(override val origin: HttpUrl) : Endpoint

        class NetSchool(val region: NetSchoolRegion) : Endpoint {
            override val origin: HttpUrl get() = region.origin
        }
    }

    override suspend fun preflight(target: DiaryTarget): Result<Unit> = attempt {
        // The phone's own catalog first: it costs no request, and a region the
        // phone would refuse to talk to is not worth asking the server about.
        endpoint(target)
        val capabilities = try {
            api.capabilities()
        } catch (failure: HttpException) {
            // A server from before registration has no such route. It could
            // not keep a session opened here, so it is found out now — before
            // a password is typed, never after one was sent to the diary.
            if (failure.code() == 404) throw DiarySignInProblem.ServerTooOld
            throw failure
        }
        if (!capabilities.registration) throw DiarySignInProblem.ServerTooOld
        if (!capabilities.enabled) throw DiarySignInProblem.ServerDisabled
        val served = when (target.provider) {
            DiaryProviderKey.PETERSBURG -> capabilities.providers.petersburg != null
            DiaryProviderKey.NETSCHOOL ->
                target.region in capabilities.providers.netschool?.regions.orEmpty()
        }
        if (!served) throw DiarySignInProblem.RegionNotServed
    }

    override suspend fun openUpstream(
        target: DiaryTarget,
        password: String,
    ): Result<UpstreamSession> = withContext(ioDispatcher) {
        val endpoint = try {
            endpoint(target)
        } catch (problem: DiarySignInProblem) {
            return@withContext Result.failure(problem)
        }
        val host = endpoint.origin.host
        try {
            // The server's own rule for a login, whole — the cleaning as well
            // as the length — applied before anything is sent: a typo costs a
            // sentence rather than an attempt, and what the diary is sent is
            // what the registration will carry.
            val login = DiaryLogin.clean(target.login) ?: throw DiarySignInProblem.LoginTooShort
            if (password.isEmpty()) throw DiarySignInProblem.WrongPassword(upstreamMessage = null)
            val cleaned = target.copy(login = login)
            val session = when (endpoint) {
                is Endpoint.Petersburg ->
                    PetersburgSignIn(client(), endpoint.origin, clock).signIn(cleaned, password)
                is Endpoint.NetSchool ->
                    NetSchoolSignIn(client(), clock).signIn(endpoint.region, cleaned, password)
            }
            Result.success(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val problem = DiarySignInProblem.of(failure, host = host)
            // `logindata` saying «Госуслуги only» names no site; the catalog does.
            Result.failure(
                if (problem is DiarySignInProblem.GosuslugiOnly && endpoint is Endpoint.NetSchool) {
                    DiarySignInProblem.GosuslugiOnly(endpoint.region.handoffUrl)
                } else {
                    problem
                },
            )
        }
    }

    override suspend fun register(upstream: UpstreamSession): Result<DiaryRegistration> =
        withContext(ioDispatcher) {
            if (upstream.spent) {
                // Registered or discarded already: a second row, or a session
                // somebody said goodbye to, is not something to send.
                return@withContext Result.failure(
                    DiarySignInProblem.Unexpected("the diary session was already used"),
                )
            }
            val response = try {
                if (Duration.between(upstream.openedAt, clock.instant()) > maxAge(upstream)) {
                    throw DiarySignInProblem.SessionAgedOut
                }
                val body = requestFor(upstream)
                    ?: throw DiarySignInProblem.ProviderUnreadable(hostOf(upstream))
                api.registerSession(body)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val problem = DiarySignInProblem.of(failure, host = null, registering = true)
                if (!problem.retryKeepsSession) discard(upstream)
                return@withContext Result.failure(problem)
            }

            // From here the session is the server's: nothing on the phone may
            // end it or send it again, whatever happens to the write below.
            upstream.spent = true
            val session = sessionFrom(response, upstream.target)
            try {
                val previous = store.currentDiaryTarget()
                if (previous == null || previous.accountKey() != session.target.accountKey()) {
                    // In this order, so there is never a moment where a new
                    // account's bearer sits over the old account's rows.
                    forgetLocal()
                    store.selectStudent(null)
                }
                store.writeDiarySession(session)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // A full disk. The server holds a session nobody here can
                // reach; it is purged after its idle window. Not a network
                // failure, whatever the exception's type says, so not retried.
                return@withContext Result.failure(
                    DiarySignInProblem.Unexpected("the session could not be stored", failure),
                )
            }
            Result.success(DiaryRegistration(session, response.students.map { it.toDomain() }))
        }

    override suspend fun discard(upstream: UpstreamSession) {
        if (upstream.spent) return
        upstream.spent = true
        when (upstream) {
            is UpstreamSession.Petersburg -> Unit // Petersburg has no logout without a browser.
            is UpstreamSession.NetSchool -> withContext(ioDispatcher) {
                val region = runCatching { directory().netschool(upstream.target.region) }.getOrNull()
                    ?: return@withContext
                NetSchoolSignIn(client(), clock).logout(upstream, region)
            }
        }
    }

    override suspend fun signIn(target: DiaryTarget, password: String): Result<DiaryRegistration> {
        preflight(target).onFailure { return Result.failure(it) }
        val upstream = openUpstream(target, password).getOrElse { return Result.failure(it) }
        return register(upstream).onFailure { discard(upstream) }
    }

    // ---- plumbing -----------------------------------------------------------

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = withContext(ioDispatcher) {
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(DiarySignInProblem.of(failure))
        }
    }

    /** The catalog's answer to «where does this target sign in», or the problem that stops it. */
    private fun endpoint(target: DiaryTarget): Endpoint {
        val catalog = directory()
        return when (target.provider) {
            DiaryProviderKey.PETERSBURG -> Endpoint.Petersburg(
                catalog.petersburg
                    ?: throw DiarySignInProblem.Unexpected("the catalog carries no Petersburg origin"),
            )
            DiaryProviderKey.NETSCHOOL -> {
                val region = catalog.netschool(target.region) ?: throw DiarySignInProblem.RegionNotServed
                // Refused here, with nothing sent anywhere: the region lets
                // people in through Госуслуги only, which this app never signs
                // in to. The browser is the way in.
                if (!region.password) throw DiarySignInProblem.GosuslugiOnly(region.handoffUrl)
                val school = target.schoolId
                if (school == null || school < 1) {
                    throw DiarySignInProblem.Unexpected("a NetSchool target without a school")
                }
                Endpoint.NetSchool(region)
            }
        }
    }

    private fun hostOf(upstream: UpstreamSession): String? =
        runCatching { endpoint(upstream.target).origin.host }.getOrNull()

    /**
     * How long a session may wait between the diary handing it over and our
     * server taking it. Registering a dead one costs an attempt against the
     * throttle and says «the diary refused the session», which would be a lie.
     *
     * «Сетевой город» says how long its sessions idle for, in milliseconds;
     * a figure under a minute is read as some other unit and ignored.
     * Petersburg says nothing, so ten minutes — far longer than the seconds a
     * registration takes, far shorter than any lifetime seen.
     */
    private fun maxAge(upstream: UpstreamSession): Duration {
        val declared = (upstream as? UpstreamSession.NetSchool)?.timeOut
            ?.takeIf { it >= MIN_DECLARED_TIMEOUT_MILLIS }
            ?.let(Duration::ofMillis)
        return declared ?: DEFAULT_MAX_AGE
    }

    /**
     * The registration body, or `null` when the session holds a value the
     * server would refuse — checked here by the server's own rules, so a
     * session the diary shaped oddly is «the diary answered strangely», not a
     * `422` that looks like a bug in the app.
     */
    private fun requestFor(upstream: UpstreamSession): DiarySessionRequestDto? {
        val target = upstream.target
        // Already clean when the session came from `openUpstream`; cleaned
        // again rather than trusted, because the server stores the cleaned
        // login and refuses, with a 422, one that cleans to under three.
        val login = DiaryLogin.clean(target.login) ?: throw DiarySignInProblem.LoginTooShort
        return when (upstream) {
            is UpstreamSession.Petersburg -> {
                val token = upstream.token
                if (token.length < MIN_JWT_LENGTH || !JWT.matches(token) || !UpstreamValues.cookieValueOk(token)) {
                    return null
                }
                DiarySessionRequestDto(
                    provider = DiaryProviderKey.PETERSBURG.wire,
                    login = login,
                    credential = credentialJson.encodeToJsonElement(PetersburgCredentialDto(token)).jsonObject,
                )
            }
            is UpstreamSession.NetSchool -> {
                val at = upstream.at
                if (at.length !in AT_LENGTH || !UpstreamValues.headerValueOk(at)) return null
                val session = upstream.cookies[NSSESSIONID]
                    ?.takeIf { it.length <= NSSESSIONID_MAX && UpstreamValues.cookieValueOk(it) }
                    ?: return null
                val security = upstream.cookies[ESRNSEC]
                    ?.takeIf { it.length <= ESRNSEC_MAX && UpstreamValues.cookieValueOk(it) }
                val credential = NetSchoolCredentialDto(
                    at = at,
                    cookies = NetSchoolCookiesDto(session = session, security = security),
                    // Left out rather than refused when it is not a version the
                    // server takes: it only acknowledges a warning page and says
                    // goodbye, and a session is worth more than either.
                    ver = upstream.ver?.takeIf { it.length <= VER_MAX && VER.matches(it) },
                    timeOut = upstream.timeOut?.takeIf { it in 0..Int.MAX_VALUE.toLong() },
                )
                DiarySessionRequestDto(
                    provider = DiaryProviderKey.NETSCHOOL.wire,
                    login = login,
                    region = target.region,
                    schoolId = target.schoolId,
                    credential = credentialJson.encodeToJsonElement(credential).jsonObject,
                )
            }
        }
    }

    /**
     * What the phone keeps: our bearer, and the target as the server now names
     * it — its zone above all, so the phone's «today» is the server's.
     */
    private fun sessionFrom(response: DiarySessionResponseDto, sent: DiaryTarget): DiarySession {
        // The login as the server stored it, which keys the corrections; what
        // was sent, cleaned the same way, if it ever stops echoing one.
        val login = DiaryLogin.clean(response.login) ?: DiaryLogin.clean(sent.login) ?: sent.login.trim()
        val target = sent.copy(
            region = response.region ?: sent.region,
            schoolId = response.schoolId ?: sent.schoolId,
            schoolName = response.schoolName?.takeIf { it.isNotBlank() } ?: sent.schoolName,
            login = login,
            zone = response.zone?.takeIf { it.isNotBlank() } ?: sent.zone,
        )
        return DiarySession(login = login, token = response.token, target = target)
    }

    private companion object {
        val DEFAULT_MAX_AGE: Duration = Duration.ofMinutes(10)
        const val MIN_DECLARED_TIMEOUT_MILLIS = 60_000L

        /** `PetersburgCredentialIn` in `server/app/schemas.py`: a JWT that can be one cookie. */
        val JWT = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*")
        const val MIN_JWT_LENGTH = 16

        /** `NetSchoolCredentialIn` and `NetSchoolCookiesIn`, bound for bound. */
        val AT_LENGTH = 8..512
        const val NSSESSIONID = "NSSESSIONID"
        const val NSSESSIONID_MAX = 128
        const val ESRNSEC = "ESRNSec"
        const val ESRNSEC_MAX = 2048
        val VER = Regex("[A-Za-z0-9._-]+")
        const val VER_MAX = 32

        /** Nulls left out: `ver` and `ESRNSec` are absent, not `null`, when there are none. */
        val credentialJson = Json { explicitNulls = false }
    }
}

package com.lumenpearson.lessons.core.data.github

import android.util.Log
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * The OAuth device flow: its state, its polling job, and the requests to
 * GitHub's login endpoints.
 *
 * One class, so that the rule [lock] carries — [signIn] and [cancelSignIn] are
 * called from the main thread while the job finishes on a worker — is read in
 * one file. The token it is granted goes into [GithubPreferences] and nowhere
 * else; the other three read it from there.
 *
 * @param scope where the polling lives; see [GithubRepositoryImpl].
 */
internal class GithubDeviceFlow(
    private val clientId: String,
    private val scope: CoroutineScope,
    private val preferences: GithubPreferences,
    userAgent: Lazy<String>,
) {

    private val userAgent: String by userAgent

    val isConfigured: Boolean = clientId.isNotBlank()

    private val mutableFlow = MutableStateFlow<DeviceFlow>(DeviceFlow.Idle)
    val flow: StateFlow<DeviceFlow> = mutableFlow.asStateFlow()

    /**
     * Guards [pollJob]. [signIn] and [cancelSignIn] are plain functions called
     * from the main thread while the job itself finishes on a worker, so a
     * `Mutex` cannot be taken here and a lock object does the same work.
     */
    private val lock = Any()
    private var pollJob: Job? = null

    fun signIn() {
        if (!isConfigured) {
            // The row is hidden without a client id, so reaching here is a
            // programming error; it still ends in a state, not an exception.
            Log.w(TAG, "GitHub sign-in requested, but no client id is configured")
            mutableFlow.value = DeviceFlow.Failed("not_configured")
            return
        }
        synchronized(lock) {
            // A second tap while a code is outstanding must not ask for a second
            // code: the first is what the browser tab is showing.
            if (pollJob?.isActive == true) return
            mutableFlow.value = DeviceFlow.Requesting
            pollJob = scope.launch { runDeviceFlow() }
        }
    }

    fun cancelSignIn() {
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = null
        }
        mutableFlow.value = DeviceFlow.Idle
    }

    /**
     * The flow from first request to verdict. Everything ends in a [DeviceFlow]
     * state; nothing escapes to the scope.
     */
    private suspend fun runDeviceFlow() {
        val outcome: DeviceFlow = try {
            val code = withContext(Dispatchers.IO) { requestDeviceCode() }
            val expiresAt = System.currentTimeMillis() + code.expiresIn * 1000L
            mutableFlow.value = DeviceFlow.AwaitingUser(
                userCode = code.userCode,
                verificationUrl = code.verificationUri,
                expiresAtMillis = expiresAt,
            )
            when (
                val verdict = pollUntilDecided(
                    interval = code.interval,
                    expiresAt = expiresAt,
                ) { withContext(Dispatchers.IO) { pollOnce(code.deviceCode) } }
            ) {
                is PollVerdict.Refused -> DeviceFlow.Failed(verdict.code)
                // Not cancellable from here on: GitHub has issued the token, and
                // a sheet closed in the same instant must not lose it — the user
                // would be typing a second code for a first token that already
                // sits in their authorised applications list.
                is PollVerdict.Granted -> withContext(NonCancellable + Dispatchers.IO) {
                    complete(verdict.token)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "GitHub sign-in failed", failure)
            DeviceFlow.Failed(failure.message ?: failure::class.java.simpleName)
        }
        mutableFlow.value = outcome
    }

    /**
     * Turns a granted token into a stored account.
     *
     * The profile call is part of signing in, not an extra: the settings row
     * shows a login, and a token with no name behind it would leave it blank.
     * Both are written in one transaction once the profile is known, so there
     * is never a token on disk without a login beside it. If the profile call
     * fails, the token is dropped and the user goes round again; GitHub
     * remembers the authorisation, so the second pass is one tap.
     */
    private suspend fun complete(token: String): DeviceFlow {
        val account = fetchAccount(token)
        preferences.write(token, account)
        return DeviceFlow.SignedIn(account)
    }

    private fun requestDeviceCode(): DeviceCodeDto {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build()
        val request = loginRequest(DEVICE_CODE_URL).post(form).build()
        return GithubApi.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val parsed = runCatching { GithubApi.json.decodeFromString(DeviceCodeDto.serializer(), body) }
                .getOrNull() ?: error("GitHub returned HTTP ${response.code}")
            if (parsed.deviceCode.isBlank() || parsed.userCode.isBlank()) {
                error(parsed.error ?: "GitHub returned no device code (HTTP ${response.code})")
            }
            parsed
        }
    }

    private fun pollOnce(deviceCode: String): PollOutcome {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", GRANT_TYPE)
            .build()
        val request = loginRequest(ACCESS_TOKEN_URL).post(form).build()
        return GithubApi.client.newCall(request).execute().use { response ->
            // Not `isSuccessful`: GitHub answers 200 to "not yet" and puts the
            // difference in the body, so the body is read whatever the status
            // and the status only matters when the body says nothing.
            val body = response.body?.string().orEmpty()
            val parsed = runCatching { GithubApi.json.decodeFromString(AccessTokenDto.serializer(), body) }
                .getOrNull() ?: error("GitHub returned HTTP ${response.code}")
            val token = parsed.accessToken
            when {
                !token.isNullOrBlank() -> PollOutcome.Granted(token)
                parsed.error == ERROR_PENDING -> PollOutcome.Pending
                parsed.error == ERROR_SLOW_DOWN -> PollOutcome.SlowDown
                else -> PollOutcome.Refused(parsed.error ?: "HTTP ${response.code}")
            }
        }
    }

    private fun fetchAccount(token: String): GithubAccount {
        val request = GithubApi.apiRequest(USER_URL, userAgent)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return GithubApi.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub returned HTTP ${response.code} for the profile")
            val user = GithubApi.json.decodeFromString(UserDto.serializer(), response.body?.string().orEmpty())
            if (user.login.isBlank()) error("GitHub returned a profile with no login")
            GithubAccount(login = user.login, avatarUrl = user.avatarUrl?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * The two OAuth endpoints live on `github.com`, not the API host, and want
     * plain `application/json`: without that header they answer in
     * `application/x-www-form-urlencoded`.
     */
    private fun loginRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", userAgent)

    private companion object {
        val DEVICE_CODE_URL = GithubApi.WEB_BASE + "/login/device/code"
        val ACCESS_TOKEN_URL = GithubApi.WEB_BASE + "/login/oauth/access_token"
        val USER_URL = GithubApi.API_BASE + "/user"

        /** Enough to open an issue on a public repository, and nothing more. */
        const val SCOPE = "public_repo"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        const val ERROR_PENDING = "authorization_pending"
        const val ERROR_SLOW_DOWN = "slow_down"
    }
}

/** What one poll of the token endpoint came back with. */
internal sealed interface PollOutcome {
    data object Pending : PollOutcome
    data object SlowDown : PollOutcome
    data class Granted(val token: String) : PollOutcome
    data class Refused(val code: String) : PollOutcome
}

/** How the polling ended. */
internal sealed interface PollVerdict {

    /** GitHub issued a token; the caller has to store it without being cancelled. */
    data class Granted(val token: String) : PollVerdict

    /** GitHub's own error code, or `expired_token` when the code ran out here. */
    data class Refused(val code: String) : PollVerdict
}

/**
 * Asks GitHub whether the code has been entered, at the interval it asked for,
 * until it says yes or no.
 *
 * Outside the repository and taking [poll] as an argument, because what this
 * decides is invisible from anywhere else: a sign-in that ended and a sign-in
 * that is still waiting look exactly alike from the sheet, which shows the
 * same code either way until a state arrives. The rule is what ends it.
 *
 * The interval is GitHub's plus one second: polling a hair early is what
 * `slow_down` punishes, and two clocks never quite agree. The RFC says a
 * `slow_down` adds five seconds, and it is added rather than substituted, so
 * repeated warnings keep backing off.
 *
 * @param poll one ask of the token endpoint.
 * @param now the wall clock, injected so the expiry can be reached without
 *   waiting a quarter of an hour for it.
 */
internal suspend fun pollUntilDecided(
    interval: Int,
    expiresAt: Long,
    now: () -> Long = System::currentTimeMillis,
    poll: suspend () -> PollOutcome,
): PollVerdict {
    var intervalSeconds = interval.coerceAtLeast(MIN_INTERVAL_SECONDS) + 1
    while (true) {
        delay(intervalSeconds * 1000L)
        // GitHub reports `expired_token` itself, but only when asked; the local
        // check keeps a code whose expiry it mis-stated from being polled for
        // the rest of the process's life.
        if (now() > expiresAt + EXPIRY_GRACE_MILLIS) {
            return PollVerdict.Refused("expired_token")
        }
        // A request that never arrived is not an answer. The user is at a
        // browser typing a code that is good for a quarter of an hour, and a
        // school network drops a request in that time as a matter of course —
        // so an `IOException` here used to leave the loop, end the sign-in as
        // «failed», and strand an authorisation that by then often already
        // existed on GitHub's side. The deadline above is what bounds this;
        // nothing else is swallowed, because anything else repeats on every
        // poll and reporting it is the only way it is ever noticed.
        val outcome = try {
            poll()
        } catch (unreachable: IOException) {
            // Not logged: it is expected, it is answered by asking again in a
            // few seconds, and a network that is down for the whole quarter of
            // an hour still ends in the `expired_token` above, which is.
            continue
        }
        when (outcome) {
            PollOutcome.Pending -> Unit
            PollOutcome.SlowDown -> intervalSeconds += SLOW_DOWN_SECONDS
            is PollOutcome.Refused -> return PollVerdict.Refused(outcome.code)
            is PollOutcome.Granted -> return PollVerdict.Granted(outcome.token)
        }
    }
}

/** GitHub's documented minimum; a response asking for less is not trusted. */
private const val MIN_INTERVAL_SECONDS = 5
private const val SLOW_DOWN_SECONDS = 5
private const val EXPIRY_GRACE_MILLIS = 30_000L

package com.lumenpearson.lessons.core.data.github

import android.content.Context
import android.util.Log
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * GitHub sign-in by device flow, and issues filed with the result.
 *
 * Internal because the container hands out the interface; there is nothing to
 * do with the class itself except build a second one, and the polling job
 * needs there to be one.
 *
 * @param scope where the polling lives. It has to outlive the sign-in sheet: the
 *   user is sent to the browser to type the code, and by the time they come
 *   back the sheet may have been dismissed, rotated away or killed with the
 *   activity. The container passes a process-wide scope for exactly this.
 */
internal class GithubRepositoryImpl(
    context: Context,
    private val clientId: String,
    private val scope: CoroutineScope,
) : GithubRepository {

    private val appContext: Context = context.applicationContext
    private val preferences = GithubPreferences(appContext)

    /** Lazy: the package info is a binder call, and most sessions never sign in. */
    private val userAgent: String by lazy {
        GithubApi.userAgent(GithubApi.installedVersion(appContext))
    }

    override val isConfigured: Boolean = clientId.isNotBlank()

    override val account: Flow<GithubAccount?> = preferences.account

    private val mutableFlow = MutableStateFlow<DeviceFlow>(DeviceFlow.Idle)
    override val flow: StateFlow<DeviceFlow> = mutableFlow.asStateFlow()

    /**
     * Guards [pollJob]. [signIn] and [cancelSignIn] are plain functions called
     * from the main thread while the job itself finishes on a worker, so a
     * `Mutex` cannot be taken here and a lock object does the same work.
     */
    private val lock = Any()
    private var pollJob: Job? = null

    override fun signIn() {
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

    override fun cancelSignIn() {
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = null
        }
        mutableFlow.value = DeviceFlow.Idle
    }

    override suspend fun signOut() {
        cancelSignIn()
        // Forgotten here, not revoked there. Revocation is
        // `DELETE /applications/{client_id}/token`, which authenticates with the
        // client secret — and this app carries none, deliberately: an APK cannot
        // keep a secret, and one that leaked would let anybody revoke every
        // user's token. So the grant stays listed under the user's authorised
        // applications on GitHub until they remove it there.
        withContext(Dispatchers.IO) {
            try {
                preferences.clear()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "Could not forget the GitHub token", failure)
            }
        }
    }

    override suspend fun fileIssue(draft: IssueDraft): IssueResult = withContext(Dispatchers.IO) {
        try {
            val token = preferences.token() ?: return@withContext IssueResult.Failed("signed_out")
            val payload = GithubApi.json.encodeToString(
                IssueRequestDto.serializer(),
                IssueRequestDto(title = draft.title, body = draft.body, labels = listOf(ISSUE_LABEL)),
            )
            val request = GithubApi.apiRequest(GithubApi.repoUrl("/issues"), userAgent)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody(GithubApi.JSON_MEDIA_TYPE))
                .build()
            GithubApi.client.newCall(request).execute().use { response ->
                when (response.code) {
                    HTTP_CREATED -> {
                        val issue = GithubApi.json.decodeFromString(
                            IssueResponseDto.serializer(),
                            response.body?.string().orEmpty(),
                        )
                        IssueResult.Filed(issue.htmlUrl)
                    }
                    HTTP_UNAUTHORISED -> {
                        // GitHub no longer honours the token: the user revoked
                        // it from their settings, or it expired there. Kept, it
                        // would fail the same way on every later try while the
                        // row still said "signed in"; cleared, the row offers
                        // sign-in again, which is the only thing that helps.
                        preferences.clear()
                        IssueResult.Failed("unauthorized")
                    }
                    else -> IssueResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not file the issue", failure)
            IssueResult.Failed(failure.message ?: failure::class.java.simpleName)
        }
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
            pollUntilDecided(code.deviceCode, code.interval, expiresAt)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "GitHub sign-in failed", failure)
            DeviceFlow.Failed(failure.message ?: failure::class.java.simpleName)
        }
        mutableFlow.value = outcome
    }

    /**
     * Asks GitHub whether the code has been entered, at the interval it asked
     * for, until it says yes or no.
     *
     * The interval is GitHub's plus one second: polling a hair early is what
     * `slow_down` punishes, and two clocks never quite agree. The RFC says a
     * `slow_down` adds five seconds, and it is added rather than substituted, so
     * repeated warnings keep backing off.
     */
    private suspend fun pollUntilDecided(deviceCode: String, interval: Int, expiresAt: Long): DeviceFlow {
        var intervalSeconds = interval.coerceAtLeast(MIN_INTERVAL_SECONDS) + 1
        while (true) {
            delay(intervalSeconds * 1000L)
            // GitHub reports `expired_token` itself, but only when asked; the
            // local check keeps a code whose expiry it mis-stated from being
            // polled for the rest of the process's life.
            if (System.currentTimeMillis() > expiresAt + EXPIRY_GRACE_MILLIS) {
                return DeviceFlow.Failed("expired_token")
            }
            when (val poll = withContext(Dispatchers.IO) { pollOnce(deviceCode) }) {
                PollOutcome.Pending -> Unit
                PollOutcome.SlowDown -> intervalSeconds += SLOW_DOWN_SECONDS
                is PollOutcome.Refused -> return DeviceFlow.Failed(poll.code)
                // Not cancellable from here on: GitHub has issued the token, and
                // a sheet closed in the same instant must not lose it — the user
                // would be typing a second code for a first token that already
                // sits in their authorised applications list.
                is PollOutcome.Granted -> return withContext(NonCancellable + Dispatchers.IO) {
                    complete(poll.token)
                }
            }
        }
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

    /** What one poll of the token endpoint came back with. */
    private sealed interface PollOutcome {
        data object Pending : PollOutcome
        data object SlowDown : PollOutcome
        data class Granted(val token: String) : PollOutcome
        data class Refused(val code: String) : PollOutcome
    }

    private companion object {
        const val TAG = "Lessons"

        val DEVICE_CODE_URL = GithubApi.WEB_BASE + "/login/device/code"
        val ACCESS_TOKEN_URL = GithubApi.WEB_BASE + "/login/oauth/access_token"
        val USER_URL = GithubApi.API_BASE + "/user"

        /** Enough to open an issue on a public repository, and nothing more. */
        const val SCOPE = "public_repo"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        /** So issues from the app can be told from ones typed on the site. */
        const val ISSUE_LABEL = "from-app"

        const val ERROR_PENDING = "authorization_pending"
        const val ERROR_SLOW_DOWN = "slow_down"

        /** GitHub's documented minimum; a response asking for less is not trusted. */
        const val MIN_INTERVAL_SECONDS = 5
        const val SLOW_DOWN_SECONDS = 5
        const val EXPIRY_GRACE_MILLIS = 30_000L

        const val HTTP_CREATED = 201
        const val HTTP_UNAUTHORISED = 401
    }
}

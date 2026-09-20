package com.lumenpearson.lessons.core.data.github

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import com.lumenpearson.lessons.core.data.repository.PullRequestResult
import com.lumenpearson.lessons.core.data.repository.TranslationChange
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
import kotlinx.coroutines.flow.first
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

    override suspend fun openTranslationPullRequest(
        changes: List<TranslationChange>,
    ): PullRequestResult = withContext(Dispatchers.IO) {
        if (changes.isEmpty()) return@withContext PullRequestResult.Failed("empty")
        try {
            val token = preferences.token()
                ?: return@withContext PullRequestResult.Failed("signed_out")
            val login = preferences.account.first()?.login
                ?: return@withContext PullRequestResult.Failed("signed_out")

            // The owner of the repository cannot fork it — GitHub answers 422 —
            // and does not need to: they can push a branch to it directly. This
            // is not a corner case, it is how the person who wrote the feature
            // will first try it.
            val ownsUpstream = login.equals(GithubApi.OWNER, ignoreCase = true)
            val head = if (ownsUpstream) GithubApi.OWNER else login
            if (!ownsUpstream && !ensureFork(token, login)) {
                return@withContext PullRequestResult.Failed("fork_unavailable")
            }

            // Cut from upstream rather than from the fork's own head. A fork
            // made once and never synced is behind by everything merged since,
            // and a branch cut from it would offer all of that back as reverts.
            // A fork's ref may be created at any commit in the parent network,
            // so this costs nothing and removes the whole class of problem.
            val baseSha = refSha(token, GithubApi.OWNER, "heads/" + BASE_BRANCH)
                ?: return@withContext PullRequestResult.Failed("no_base")
            val branch = BRANCH_PREFIX + System.currentTimeMillis()
            if (!createBranch(token, head, branch, baseSha)) {
                return@withContext PullRequestResult.Failed("branch_refused")
            }

            val refused = mutableListOf<String>()
            var written = 0
            for ((path, forPath) in changes.groupBy { it.path }) {
                val file = contents(token, GithubApi.OWNER, path, baseSha)
                if (file == null) {
                    // The path comes from the key's prefix. A path that is not
                    // in the repository means that rule has drifted, which is
                    // this app's bug and not the reader's; every key meant for
                    // the file is reported rather than one of them.
                    forPath.forEach { refused += it.key }
                    continue
                }
                var document = file.first
                for (change in forPath) {
                    val patched = StringsDocument.replace(document, change.key, change.body)
                    if (patched == null) refused += change.key else document = patched
                }
                if (document == file.first) continue
                if (!putContents(token, head, path, document, file.second, branch)) {
                    return@withContext PullRequestResult.Failed("write_refused")
                }
                written++
            }
            // Every correction was refused, so there is nothing to review. An
            // empty pull request would be worse than this failure: it would look
            // like the work had been delivered.
            if (written == 0) return@withContext PullRequestResult.Failed("nothing_applied")

            val url = openPullRequest(token, head, branch, changes.size - refused.size, login)
                ?: return@withContext PullRequestResult.Failed("pull_refused")
            PullRequestResult.Opened(url, refused)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not open the translation pull request", failure)
            PullRequestResult.Failed(failure.message ?: failure::class.java.simpleName)
        }
    }

    /**
     * A fork of this project under [login], creating one if there is none.
     *
     * `POST /forks` answers `202 Accepted` and does the work afterwards, so the
     * fork is not there when the call returns. Polling is bounded: a fork that
     * has not appeared in [FORK_ATTEMPTS] tries is reported as unavailable
     * rather than waited on for ever behind a spinner.
     */
    private suspend fun ensureFork(token: String, login: String): Boolean {
        if (repositoryExists(token, login)) return true
        val request = GithubApi.apiRequest(GithubApi.repoUrl("/forks"), userAgent)
            .header("Authorization", "Bearer $token")
            .post(EMPTY_JSON.toRequestBody(GithubApi.JSON_MEDIA_TYPE))
            .build()
        GithubApi.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
        }
        repeat(FORK_ATTEMPTS) {
            delay(FORK_POLL_MILLIS)
            if (repositoryExists(token, login)) return true
        }
        return false
    }

    private suspend fun repositoryExists(token: String, login: String): Boolean {
        val url = GithubApi.API_BASE + "/repos/" + login + "/" + GithubApi.REPO
        return get(token, url) != null
    }

    private suspend fun refSha(token: String, owner: String, ref: String): String? {
        val url = GithubApi.API_BASE + "/repos/" + owner + "/" + GithubApi.REPO + "/git/ref/" + ref
        val body = get(token, url) ?: return null
        return GithubApi.json.decodeFromString(RefDto.serializer(), body).target.sha
            .takeIf { it.isNotBlank() }
    }

    private fun createBranch(token: String, owner: String, branch: String, sha: String): Boolean {
        val payload = GithubApi.json.encodeToString(
            CreateRefRequestDto.serializer(),
            CreateRefRequestDto(ref = "refs/heads/$branch", sha = sha),
        )
        val url = GithubApi.API_BASE + "/repos/" + owner + "/" + GithubApi.REPO + "/git/refs"
        val request = GithubApi.apiRequest(url, userAgent)
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(GithubApi.JSON_MEDIA_TYPE))
            .build()
        GithubApi.client.newCall(request).execute().use { response ->
            return response.code == HTTP_CREATED
        }
    }

    /** The file at [path] and its blob sha, or `null` when it is not there. */
    private suspend fun contents(
        token: String,
        owner: String,
        path: String,
        ref: String,
    ): Pair<String, String>? {
        val url = GithubApi.API_BASE + "/repos/" + owner + "/" + GithubApi.REPO +
            "/contents/" + path + "?ref=" + ref
        val body = get(token, url) ?: return null
        val dto = GithubApi.json.decodeFromString(ContentsDto.serializer(), body)
        if (dto.content.isBlank() || dto.sha.isBlank()) return null
        // GitHub wraps the base64 at sixty characters, and a decoder not told to
        // ignore those breaks returns nothing at all rather than failing.
        val decoded = Base64.decode(dto.content, Base64.DEFAULT).toString(Charsets.UTF_8)
        return decoded to dto.sha
    }

    private fun putContents(
        token: String,
        owner: String,
        path: String,
        document: String,
        sha: String,
        branch: String,
    ): Boolean {
        val payload = GithubApi.json.encodeToString(
            UpdateContentsRequestDto.serializer(),
            UpdateContentsRequestDto(
                message = COMMIT_MESSAGE,
                content = Base64.encodeToString(document.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                sha = sha,
                branch = branch,
            ),
        )
        val url = GithubApi.API_BASE + "/repos/" + owner + "/" + GithubApi.REPO +
            "/contents/" + path
        val request = GithubApi.apiRequest(url, userAgent)
            .header("Authorization", "Bearer $token")
            .put(payload.toRequestBody(GithubApi.JSON_MEDIA_TYPE))
            .build()
        GithubApi.client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun openPullRequest(
        token: String,
        head: String,
        branch: String,
        applied: Int,
        login: String,
    ): String? {
        val payload = GithubApi.json.encodeToString(
            PullRequestRequestDto.serializer(),
            PullRequestRequestDto(
                title = PULL_TITLE,
                // Said in English, like everything else written about this
                // project, and naming the count rather than listing the keys:
                // the diff lists them, and a body that repeats it goes stale
                // the moment a reviewer pushes a change to the branch.
                body = "$applied correction(s) to the app's strings, sent from the app by @$login.",
                head = if (head == GithubApi.OWNER) branch else "$head:$branch",
                base = BASE_BRANCH,
            ),
        )
        val request = GithubApi.apiRequest(GithubApi.repoUrl("/pulls"), userAgent)
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(GithubApi.JSON_MEDIA_TYPE))
            .build()
        GithubApi.client.newCall(request).execute().use { response ->
            if (response.code != HTTP_CREATED) return null
            return GithubApi.json.decodeFromString(
                PullRequestResponseDto.serializer(),
                response.body?.string().orEmpty(),
            ).htmlUrl.takeIf { it.isNotBlank() }
        }
    }

    /**
     * A GET returning the body, or `null` for anything that is not a 200.
     *
     * A 401 clears the token for the same reason [fileIssue] does: GitHub no
     * longer honours it, and kept, it would fail this way on every later try
     * while the row still said "signed in".
     */
    private suspend fun get(token: String, url: String): String? {
        val request = GithubApi.apiRequest(url, userAgent)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        GithubApi.client.newCall(request).execute().use { response ->
            if (response.code == HTTP_UNAUTHORISED) preferences.clear()
            return if (response.isSuccessful) response.body?.string() else null
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

        /**
         * Where a correction is offered. `main` rather than `dev`: `dev` is the
         * maintainer's working branch and is restarted from `main` after every
         * merge, so a pull request aimed at it would lose its base under it.
         */
        const val BASE_BRANCH = "main"

        /**
         * Branch names end in the clock rather than in the reader's name or a
         * hash of the edits. A second session correcting the same string must
         * not collide with the first, and a reader who sends two sets an hour
         * apart should see two pull requests, not a refusal.
         */
        const val BRANCH_PREFIX = "translation/from-app-"

        const val COMMIT_MESSAGE = "Correct a string from inside the app"
        const val PULL_TITLE = "Corrections to the app's strings, sent from the app"

        /** `POST /forks` takes no body, and GitHub refuses one that is not JSON. */
        const val EMPTY_JSON = "{}"

        /**
         * A fork appears a second or two after GitHub accepts the request, and
         * occasionally later. Bounded because the reader is looking at a
         * spinner: about half a minute, then an answer either way.
         */
        const val FORK_ATTEMPTS = 15
        const val FORK_POLL_MILLIS = 2_000L

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

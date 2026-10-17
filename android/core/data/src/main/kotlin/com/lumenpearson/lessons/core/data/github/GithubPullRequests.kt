package com.lumenpearson.lessons.core.data.github

import android.util.Base64
import android.util.Log
import com.lumenpearson.lessons.core.data.repository.PullRequestResult
import com.lumenpearson.lessons.core.data.repository.TranslationChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A translation correction offered as a pull request from the reader's own
 * account: the fork, a branch cut from upstream, the files patched, the pull
 * request opened.
 *
 * [get] is this class's own, because only these helpers read a body by URL.
 * [openTranslationPullRequest] reads the token from [GithubPreferences] once
 * and hands it to every request after it, [get] included. Only a `401` that
 * [get] receives clears it; one answering the fork's request, the branch, a
 * file's write or the pull request itself does not.
 */
internal class GithubPullRequests(
    private val preferences: GithubPreferences,
    userAgent: Lazy<String>,
    /**
     * The repository the correction is offered to: the one the build names
     * (#469), so a fork's reader corrects the fork's strings, in the fork.
     */
    private val project: GithubProject,
) {

    private val userAgent: String by userAgent

    suspend fun openTranslationPullRequest(
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
            val ownsUpstream = login.equals(project.owner, ignoreCase = true)
            val head = if (ownsUpstream) project.owner else login
            if (!ownsUpstream && !ensureFork(token, login)) {
                return@withContext PullRequestResult.Failed("fork_unavailable")
            }

            // Cut from upstream rather than from the fork's own head. A fork
            // made once and never synced is behind by everything merged since,
            // and a branch cut from it would offer all of that back as reverts.
            // A fork's ref may be created at any commit in the parent network,
            // so this costs nothing and removes the whole class of problem.
            val baseSha = refSha(token, project.owner, "heads/" + BASE_BRANCH)
                ?: return@withContext PullRequestResult.Failed("no_base")
            val branch = BRANCH_PREFIX + System.currentTimeMillis()
            if (!createBranch(token, head, branch, baseSha)) {
                return@withContext PullRequestResult.Failed("branch_refused")
            }

            val refused = mutableListOf<String>()
            var written = 0

            // Fetched once each and kept, because a session of corrections
            // spreads across a handful of files and several of them land in
            // the same one.
            val fetched = mutableMapOf<String, Pair<String, String>?>()
            suspend fun fileAt(path: String): Pair<String, String>? =
                fetched.getOrPut(path) { contents(token, project.owner, path, baseSha) }

            // Which file a key lives in is asked of the files, not guessed.
            // The key's prefix picks the module and can go no further: inside
            // `:app` the prefixes overlap — `settings_` is in `strings.xml`
            // and in `strings_admin.xml` — so the old single guess of
            // `strings.xml` refused 419 of that module's 779 strings, every
            // one of them with «Не отправлено» and no reason a reader could
            // act on.
            val resolved = mutableMapOf<String, MutableList<TranslationChange>>()
            for (change in changes) {
                val path = change.paths.firstOrNull { candidate ->
                    val document = fileAt(candidate)?.first
                    document != null &&
                        StringsDocument.replace(document, change.key, change.body) != null
                }
                if (path == null) refused += change.key
                else resolved.getOrPut(path) { mutableListOf() } += change
            }

            for ((path, forPath) in resolved) {
                val file = fileAt(path) ?: continue
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
        val request = GithubApi.apiRequest(GithubApi.repoUrl(project, "/forks"), userAgent)
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

    /**
     * Whether [login] already has a fork **of this project**.
     *
     * Not "a repository of that name": `lessons` is a common enough word that a
     * reader may own an unrelated one, and treating it as the fork would cut a
     * branch and commit into somebody's own work. The parent is what decides,
     * and a repository that is not a fork at all never qualifies.
     *
     * The parent is the build's repository (#469), so on a build of somebody's
     * fork a reader whose fork is of `lumenpearson/lessons` never qualifies,
     * and GitHub, which keeps one fork per account per network, answers the
     * request for a new one with that same fork: [ensureFork] polls out and
     * the result is «fork_unavailable», with nothing written anywhere.
     * `docs/build.md`, "What the build is told about itself", says so too.
     */
    private suspend fun repositoryExists(token: String, login: String): Boolean {
        val body = get(token, GithubApi.repoUrl(under(login), "")) ?: return false
        val dto = GithubApi.json.decodeFromString(RepositoryDto.serializer(), body)
        return dto.fork && dto.parent?.fullName.equals(project.fullName, ignoreCase = true)
    }

    private suspend fun refSha(token: String, owner: String, ref: String): String? {
        val url = GithubApi.repoUrl(under(owner), "/git/ref/$ref")
        val body = get(token, url) ?: return null
        return GithubApi.json.decodeFromString(RefDto.serializer(), body).target.sha
            .takeIf { it.isNotBlank() }
    }

    private fun createBranch(token: String, owner: String, branch: String, sha: String): Boolean {
        val payload = GithubApi.json.encodeToString(
            CreateRefRequestDto.serializer(),
            CreateRefRequestDto(ref = "refs/heads/$branch", sha = sha),
        )
        val url = GithubApi.repoUrl(under(owner), "/git/refs")
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
        val url = GithubApi.repoUrl(under(owner), "/contents/$path?ref=$ref")
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
        val url = GithubApi.repoUrl(under(owner), "/contents/$path")
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
                head = if (head == project.owner) branch else "$head:$branch",
                base = BASE_BRANCH,
            ),
        )
        val request = GithubApi.apiRequest(GithubApi.repoUrl(project, "/pulls"), userAgent)
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
     * The repository of this name under [owner]: the project itself, or the
     * reader's fork of it, which GitHub names after its parent.
     */
    private fun under(owner: String): GithubProject = project.copy(owner = owner)

    /**
     * A GET returning the body, or `null` for anything that is not a 200.
     *
     * A 401 clears the token for the same reason [GithubIssues.fileIssue] does:
     * GitHub no longer honours it, and kept, it would fail this way on every
     * later try while the row still said "signed in".
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

    private companion object {
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
    }
}

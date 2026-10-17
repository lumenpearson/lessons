package com.lumenpearson.lessons.core.data.github

import android.util.Log
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * An issue filed as the signed-in account.
 *
 * With no label (#458). It used to ask for `from-app`, and GitHub drops a label
 * from this request without a word for anybody who cannot push to the
 * repository — every reader — so not one report ever carried it, and the label
 * itself was never even created. What marks a report as the app's is the last
 * line of its body, [BugReportComposer.APP_MARK], which the browser path
 * carries too; a maintainer labels it by hand.
 *
 * Reads the token from [GithubPreferences] on every call, and clears it on a
 * `401` itself, as [GithubPullRequests] and [GithubPermissions] do.
 */
internal class GithubIssues(
    private val preferences: GithubPreferences,
    userAgent: Lazy<String>,
    private val project: GithubProject,
) {

    private val userAgent: String by userAgent

    suspend fun fileIssue(draft: IssueDraft): IssueResult = withContext(Dispatchers.IO) {
        try {
            val token = preferences.token() ?: return@withContext IssueResult.Failed("signed_out")
            val payload = requestBody(draft)
            val request = GithubApi.apiRequest(GithubApi.repoUrl(project, "/issues"), userAgent)
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

    internal companion object {
        /** The JSON this sends: the title and the body, and nothing to be dropped. */
        fun requestBody(draft: IssueDraft): String = GithubApi.json.encodeToString(
            IssueRequestDto.serializer(),
            IssueRequestDto(title = draft.title, body = draft.body),
        )
    }
}

package com.lumenpearson.lessons.core.data.github

import android.content.Context
import android.util.Log
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import com.lumenpearson.lessons.core.data.repository.PermissionsAnswer
import com.lumenpearson.lessons.core.data.repository.PullRequestResult
import com.lumenpearson.lessons.core.data.repository.TranslationChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * GitHub sign-in by device flow, and issues filed with the result.
 *
 * Its four concerns are four classes — [GithubDeviceFlow], [GithubPullRequests],
 * [GithubIssues] and [GithubPermissions] — and this one hands them out behind
 * the interface. They share [GithubPreferences] rather than a token, and none
 * of them holds one: [GithubDeviceFlow] writes it there and never reads it,
 * the other three read it there when they are called, and a `401` answering
 * the issue, the permissions read or one of the pull request's reads clears
 * it, so a token GitHub stopped honouring is forgotten where it is found. They
 * share one lazy user agent too, which is asked for once.
 *
 * Internal because the container hands out the interface; there is nothing to
 * do with the class itself except build a second one, and the polling job
 * needs there to be one.
 *
 * @param scope where the polling lives. It has to outlive the sign-in sheet: the
 *   user is sent to the browser to type the code, and by the time they come
 *   back the sheet may have been dismissed, rotated away or killed with the
 *   activity. The container passes a process-wide scope for exactly this.
 * @param project the repository the build names, which the issue, the pull
 *   request and the permissions question all go to (#469).
 */
internal class GithubRepositoryImpl(
    context: Context,
    clientId: String,
    scope: CoroutineScope,
    project: GithubProject,
) : GithubRepository {

    private val appContext: Context = context.applicationContext
    private val preferences = GithubPreferences(appContext)

    /** Lazy: the package info is a binder call, and most sessions never sign in. */
    private val userAgent: Lazy<String> = lazy {
        GithubApi.userAgent(GithubApi.installedVersion(appContext))
    }

    private val deviceFlow = GithubDeviceFlow(clientId, scope, preferences, userAgent)
    private val issues = GithubIssues(preferences, userAgent, project)
    private val pullRequests = GithubPullRequests(preferences, userAgent, project)
    private val permissions = GithubPermissions(preferences, userAgent, project)

    override val isConfigured: Boolean = deviceFlow.isConfigured

    override val account: Flow<GithubAccount?> = preferences.account

    override val flow: StateFlow<DeviceFlow> = deviceFlow.flow

    override fun signIn() = deviceFlow.signIn()

    override fun cancelSignIn() = deviceFlow.cancelSignIn()

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

    override suspend fun fileIssue(draft: IssueDraft): IssueResult = issues.fileIssue(draft)

    override suspend fun openTranslationPullRequest(changes: List<TranslationChange>): PullRequestResult =
        pullRequests.openTranslationPullRequest(changes)

    override suspend fun repositoryPermissions(): PermissionsAnswer = permissions.repositoryPermissions()
}

// Shared by the repository and the four classes it hands its work to, which live
// in files of their own: Kotlin's `private` ends at the file.
internal const val TAG = "Lessons"
internal const val HTTP_CREATED = 201
internal const val HTTP_UNAUTHORISED = 401

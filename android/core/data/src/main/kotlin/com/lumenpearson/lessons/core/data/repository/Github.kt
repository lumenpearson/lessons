package com.lumenpearson.lessons.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in GitHub user, as much of them as the settings row shows.
 *
 * @property login the handle, "octocat".
 * @property avatarUrl for the row's tile; `null` when the profile has none.
 */
data class GithubAccount(
    val login: String,
    val avatarUrl: String?,
)

/**
 * The OAuth device flow, step by step.
 *
 * GitHub's device flow is the only OAuth grant that works for an app with no
 * server and no custom URL scheme: the app asks GitHub for a short code, the
 * user types it on a GitHub page in their browser, and the app polls until
 * GitHub says the code was entered. Every state here is one of those steps.
 */
sealed interface DeviceFlow {

    data object Idle : DeviceFlow

    /** Asking GitHub for a code. */
    data object Requesting : DeviceFlow

    /**
     * The code is ready. The sheet shows [userCode] and opens [verificationUrl];
     * the repository keeps polling in the background until [expiresAtMillis].
     */
    data class AwaitingUser(
        val userCode: String,
        val verificationUrl: String,
        val expiresAtMillis: Long,
    ) : DeviceFlow

    data class SignedIn(val account: GithubAccount) : DeviceFlow

    /**
     * @property reason GitHub's error code where there was one
     *   (`expired_token`, `access_denied`) or an exception message otherwise.
     *   For the debug log; the sheet shows a generic line.
     */
    data class Failed(val reason: String?) : DeviceFlow
}

/**
 * What a bug report carries to GitHub.
 *
 * @property title the first line of the description, cut short.
 * @property body the description followed by the device block, in Markdown.
 */
data class IssueDraft(
    val title: String,
    val body: String,
)

/** Outcome of filing an issue. */
sealed interface IssueResult {
    data class Filed(val htmlUrl: String) : IssueResult
    data class Failed(val reason: String?) : IssueResult
}

/**
 * GitHub sign-in and the one thing it is for: filing issues from the app.
 *
 * The client id is the app's own, registered by whoever builds it; there is no
 * secret, because the device flow needs none and an APK cannot keep one.
 */
interface GithubRepository {

    /** `false` when the build carries no client id; the sign-in row hides then. */
    val isConfigured: Boolean

    /** The signed-in account, or `null`. Emits on every change. */
    val account: Flow<GithubAccount?>

    val flow: StateFlow<DeviceFlow>

    /**
     * Starts the device flow. Idempotent while one is running: a second call
     * during [DeviceFlow.AwaitingUser] does not request a second code.
     */
    fun signIn()

    /** Stops polling and forgets a code that was never entered. */
    fun cancelSignIn()

    suspend fun signOut()

    /** Files [draft] as the signed-in user. Requires an account. */
    suspend fun fileIssue(draft: IssueDraft): IssueResult
}

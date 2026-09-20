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
 * One corrected string, addressed to the file it lives in.
 *
 * @property path the file's path in the repository, module and all —
 *   `android/core/designsystem/src/main/res/values-en/strings.xml`. Worked out
 *   by the caller from the key's prefix, because the merged resources a running
 *   app sees cannot say which module a string came from.
 * @property key the resource name, `ds_lesson_now`.
 * @property body the new value, **already escaped** for a resource file. The
 *   escaping lives with the reader-facing half of the feature, which is where
 *   the reasoning about `%`, quotes and a leading `@` already is; a second
 *   escaper here would disagree with it within a month.
 */
data class TranslationChange(
    val path: String,
    val key: String,
    val body: String,
)

/** Outcome of offering a session of corrections as a pull request. */
sealed interface PullRequestResult {

    /**
     * @property htmlUrl the pull request, to be opened and to be shown.
     * @property refused corrections whose key was not in the file its prefix
     *   pointed at. Reported rather than dropped: a prefix rule that has
     *   drifted is a bug in this app, and a reader who corrected ten strings
     *   and got nine deserves to be told which one did not travel.
     */
    data class Opened(val htmlUrl: String, val refused: List<String>) : PullRequestResult

    data class Failed(val reason: String?) : PullRequestResult
}

/**
 * GitHub sign-in and the two things it is for: filing issues from the app, and
 * offering a translation correction as a pull request from the reader's own
 * account.
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

    /**
     * Opens a pull request carrying [changes], as the signed-in user.
     *
     * The branch is cut from the upstream commit rather than from whatever the
     * reader's fork happens to point at: a fork made a year ago and never
     * touched since is behind, and a pull request cut from it would carry every
     * commit made in between as a revert. GitHub lets a fork's ref be created
     * at a commit from the parent network, which is what makes that possible
     * and costs nothing.
     *
     * Requires an account. Signed out it fails rather than prompting: the
     * button that reaches this is disabled without one, and a repository that
     * opened a sign-in sheet would be deciding something the screen owns.
     */
    suspend fun openTranslationPullRequest(changes: List<TranslationChange>): PullRequestResult
}

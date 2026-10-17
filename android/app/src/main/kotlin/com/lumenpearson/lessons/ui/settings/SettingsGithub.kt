package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import com.lumenpearson.lessons.core.data.repository.PullRequestResult
import com.lumenpearson.lessons.core.data.repository.TranslationChange
import com.lumenpearson.lessons.ui.common.SyncMessage
import com.lumenpearson.lessons.ui.translate.TranslationMode
import com.lumenpearson.lessons.ui.translate.TranslationSubmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the page sends to GitHub as the signed-in account: a bug report, and a
 * session of translation corrections as a pull request.
 *
 * Owns [filingIssue], which the view model's `remote` combine reads, and the
 * pull request's progress. [message] is the view model's, not this class's:
 * the sync writes it too, and the page shows whichever wrote last, so this
 * class is handed the flow itself. Launches in [viewModelScope], the view
 * model's; it never makes a scope of its own.
 */
internal class SettingsGithub(
    private val githubRepository: GithubRepository,
    private val message: MutableStateFlow<SyncMessage?>,
    private val viewModelScope: CoroutineScope,
) {

    val filingIssue = MutableStateFlow(false)

    private val mutableSubmit = MutableStateFlow<TranslationSubmit>(TranslationSubmit.Idle)

    /** How the last attempt to send a session of corrections is going. */
    internal val translationSubmit: StateFlow<TranslationSubmit> = mutableSubmit.asStateFlow()

    /**
     * Offers the session of corrections as a pull request.
     *
     * Launched here rather than from the sheet because the sheet can be
     * dismissed while GitHub is still forking, and a `rememberCoroutineScope`
     * would take the work with it — possibly after the branch had been created,
     * which leaves a stray branch and no pull request. `viewModelScope` lives
     * as long as the settings screen, which is long enough.
     *
     * The session is cleared only on a pull request that exists, and by the
     * thing that knows it exists. Essentials clears it on a posted comment,
     * which is not the same event.
     */
    fun submitCorrections(changes: List<TranslationChange>) {
        if (mutableSubmit.value is TranslationSubmit.Sending) return
        mutableSubmit.value = TranslationSubmit.Sending
        viewModelScope.launch {
            mutableSubmit.value = when (val result =
                githubRepository.openTranslationPullRequest(changes)) {
                is PullRequestResult.Opened -> {
                    TranslationMode.clear()
                    TranslationSubmit.Opened(result.htmlUrl, result.refused)
                }
                is PullRequestResult.Failed -> TranslationSubmit.Failed
            }
        }
    }

    /** Called once the pull request has been shown, so it is not opened twice. */
    fun acknowledgeSubmission() {
        mutableSubmit.value = TranslationSubmit.Idle
    }

    /**
     * Files a bug report as the signed-in user.
     *
     * @param onFiled called with the new issue's page on success, so the sheet
     *   can offer to open it. Failure surfaces as a snackbar.
     */
    fun fileIssue(draft: IssueDraft, onFiled: (url: String) -> Unit) {
        if (filingIssue.value) return
        viewModelScope.launch {
            filingIssue.value = true
            val result = githubRepository.fileIssue(draft)
            filingIssue.value = false
            when (result) {
                is IssueResult.Filed -> onFiled(result.htmlUrl)
                is IssueResult.Failed -> message.value = SyncMessage.IssueFailed
            }
        }
    }
}

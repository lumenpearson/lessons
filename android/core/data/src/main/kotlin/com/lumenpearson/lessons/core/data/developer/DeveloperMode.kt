package com.lumenpearson.lessons.core.data.developer

import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.PermissionsAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex

/**
 * The hidden developer section (#237): whether it has been found, whether the
 * account signed in to GitHub may open it, and which tools are on.
 *
 * **The gate is not a security boundary, and nothing behind it may need one.**
 * An APK can be patched past any check made on the phone. So the mode unlocks
 * nothing the server would refuse and holds no secret; it shows the phone what
 * it already knows about itself. The gate keeps the tools out of everybody
 * else's way, which is all a gate on the client can do.
 */
interface DeveloperMode {

    val state: StateFlow<DeveloperState>

    /** Lists the section in the settings. Grants nothing by itself. */
    suspend fun reveal()

    /** Unlists it and forgets everything: the verdict, the switches, and through them the logs. */
    suspend fun hide()

    /** Asks GitHub now, whatever the stored verdict says. One at a time; a second call is dropped. */
    suspend fun verify()

    /**
     * Asks GitHub only when the section has been found and no verdict stands
     * for the account signed in — so a launch costs a request at most once a
     * day, and an install where nobody found the section never asks at all.
     */
    suspend fun verifyIfStale()

    suspend fun setTool(tool: DeveloperTool, on: Boolean)
}

internal class DeveloperModeImpl(
    private val store: DeveloperStore,
    private val github: GithubRepository,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : DeveloperMode {

    private val checking = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)
    private val asking = Mutex()

    override val state: StateFlow<DeveloperState> = combine(
        store.stored,
        github.account,
        checking,
        failure,
    ) { stored, account, isChecking, reason ->
        DeveloperState(
            revealed = stored.revealed,
            access = accessOf(stored.verdict, account?.login, isChecking, reason, now()),
            chosen = stored.tools,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DeveloperState())

    override suspend fun reveal() {
        store.update { it.copy(revealed = true) }
    }

    override suspend fun hide() {
        failure.value = null
        store.update { DeveloperStored() }
    }

    /**
     * A failure to ask leaves the stored verdict where it was: GitHub being
     * down for an hour must not switch the tools off in the middle of the very
     * investigation they were switched on for. The verdict still expires a day
     * after it was given, which is what bounds that.
     */
    override suspend fun verify() {
        if (!asking.tryLock()) return
        try {
            checking.value = true
            when (val answer = github.repositoryPermissions()) {
                PermissionsAnswer.SignedOut -> {
                    failure.value = null
                    store.update { it.copy(verdict = null) }
                }
                is PermissionsAnswer.Known -> {
                    failure.value = null
                    val verdict = DeveloperVerdict(answer.login, developerRoleOf(answer.permissions), now())
                    store.update { it.copy(verdict = verdict) }
                }
                is PermissionsAnswer.Failed -> failure.value = answer.reason
            }
        } finally {
            checking.value = false
            asking.unlock()
        }
    }

    override suspend fun verifyIfStale() {
        val stored = store.stored.first()
        val login = github.account.first()?.login
        if (stored.revealed && login != null && stored.verdict?.standsFor(login, now()) != true) verify()
    }

    override suspend fun setTool(tool: DeveloperTool, on: Boolean) {
        store.update { it.copy(tools = if (on) it.tools + tool else it.tools - tool) }
    }
}

package com.lumenpearson.lessons.ui.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.BuildConfig
import com.lumenpearson.lessons.core.data.developer.BuildFacts
import com.lumenpearson.lessons.core.data.developer.CheckResult
import com.lumenpearson.lessons.core.data.developer.DeveloperChecks
import com.lumenpearson.lessons.core.data.developer.DeveloperMode
import com.lumenpearson.lessons.core.data.developer.DeveloperState
import com.lumenpearson.lessons.core.data.developer.DeveloperTool
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.diagnostics.ActivityEntry
import com.lumenpearson.lessons.core.data.diagnostics.ActivityLog
import com.lumenpearson.lessons.core.data.diagnostics.NetworkEntry
import com.lumenpearson.lessons.core.data.diagnostics.NetworkLog
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the checks stand: not run, running, or run at [Done.atMillis]. */
sealed interface ChecksState {
    data object Idle : ChecksState
    data object Running : ChecksState
    data class Done(val results: List<CheckResult>, val atMillis: Long) : ChecksState
}

/**
 * The developer page, minus the two records, which are read straight from
 * their own flows: they grow on every request and every event, and folding
 * them into this state would rebuild it each time.
 */
data class DeveloperUiState(
    val mode: DeveloperState = DeveloperState(),
    val account: GithubAccount? = null,
    val githubConfigured: Boolean = false,
    val signIn: DeviceFlow = DeviceFlow.Idle,
    val checks: ChecksState = ChecksState.Idle,
)

/**
 * The developer page's state holder (#237).
 *
 * The page asks GitHub on the way in when no verdict stands, and again the
 * moment an account signs in through it — which is the whole of a first visit:
 * sign in, and the tools open or the page says why not.
 */
class DeveloperViewModel(
    private val mode: DeveloperMode,
    private val github: GithubRepository,
    private val checks: DeveloperChecks,
    private val build: BuildFacts,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val checksState = MutableStateFlow<ChecksState>(ChecksState.Idle)

    val uiState: StateFlow<DeveloperUiState> = combine(
        mode.state,
        github.account,
        github.flow,
        checksState,
    ) { state, account, signIn, checksNow ->
        DeveloperUiState(
            mode = state,
            account = account,
            githubConfigured = github.isConfigured,
            signIn = signIn,
            checks = checksNow,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(StopTimeoutMillis),
        initialValue = DeveloperUiState(mode = mode.state.value, githubConfigured = github.isConfigured),
    )

    val network: StateFlow<List<NetworkEntry>> = NetworkLog.entries

    val activity: StateFlow<List<ActivityEntry>> = ActivityLog.entries

    init {
        viewModelScope.launch { mode.verifyIfStale() }
        // A sign-in made from this page is a request to be checked: asked at
        // once rather than on the next visit.
        viewModelScope.launch {
            github.account
                .map { it?.login }
                .distinctUntilChanged()
                .drop(1)
                .collect { login -> if (login != null) mode.verify() }
        }
    }

    fun verify() {
        viewModelScope.launch { mode.verify() }
    }

    fun signIn() = github.signIn()

    fun cancelSignIn() = github.cancelSignIn()

    fun setTool(tool: DeveloperTool, on: Boolean) {
        viewModelScope.launch { mode.setTool(tool, on) }
    }

    fun runChecks() {
        if (checksState.value == ChecksState.Running) return
        checksState.value = ChecksState.Running
        viewModelScope.launch { checksState.value = ChecksState.Done(checks.run(build), now()) }
    }

    fun clearNetwork() = NetworkLog.clear()

    fun clearActivity() = ActivityLog.clear()

    fun hide() {
        viewModelScope.launch { mode.hide() }
    }

    /** Everything on the page as one plain text, for a message or an issue; see [deviceZone]. */
    fun report(): String = DeveloperReport.text(
        state = uiState.value,
        network = network.value,
        activity = activity.value,
        nowMillis = now(),
        zone = deviceZone(),
    )

    companion object {
        private const val StopTimeoutMillis = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeveloperViewModel(
                    mode = Graph.container.developerMode,
                    github = Graph.container.githubRepository,
                    checks = Graph.container.developerChecks,
                    build = BuildFacts(
                        version = BuildConfig.VERSION_NAME,
                        commit = BuildConfig.BUILD_COMMIT,
                        buildType = BuildConfig.BUILD_TYPE,
                    ),
                )
            }
        }
    }
}

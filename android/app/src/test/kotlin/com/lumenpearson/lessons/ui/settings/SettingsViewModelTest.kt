package com.lumenpearson.lessons.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.lumenpearson.lessons.core.data.developer.DeveloperMode
import com.lumenpearson.lessons.core.data.developer.DeveloperState
import com.lumenpearson.lessons.core.data.developer.DeveloperTool
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import com.lumenpearson.lessons.core.data.repository.PullRequestResult
import com.lumenpearson.lessons.core.data.repository.ReleaseInfo
import com.lumenpearson.lessons.core.data.repository.ServerStatus
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.data.repository.TranslationChange
import com.lumenpearson.lessons.core.data.repository.UpdateCheck
import com.lumenpearson.lessons.core.data.repository.UpdateRepository
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.ui.admin.FakeSessionRepository
import com.lumenpearson.lessons.ui.common.SyncMessage
import com.lumenpearson.lessons.ui.onboarding.FakeSettings
import com.lumenpearson.lessons.ui.translate.TranslationSubmit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the settings page's view model publishes and writes, held before it is
 * split into the update check, the GitHub work and the Telegram link.
 *
 * Nothing tested the class before this. Each case is one thing that split
 * could break without a compile error: a flow read before it is built, a
 * flow two pieces write, a launch in a scope that outlives the page,
 * `stateIn` moved or started otherwise, or a value read once instead of at
 * the call. Every fake answers at once unless a test holds it with a gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val settings = FakeSettings()
    private val sessions = CountingSessions()
    private val timetables = Timetables()
    private val updates = Updates()
    private val github = Github()
    private val links = Links()
    private val developer = Developer()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = SettingsViewModel(settings, sessions, timetables, updates, github, links, developer)

    /** The page on screen: `uiState` is shared while subscribed, so something subscribes. */
    private fun TestScope.watched(built: SettingsViewModel = model()): SettingsViewModel {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { built.uiState.collect {} }
        return built
    }

    @Test
    fun `while it is watched the page publishes the settings, the classes and every remote field`() = runTest {
        links.roles.value = mapOf(1L to ClassRole.EDITOR)
        val model = watched()

        val session = checkNotNull(sessions.inner.session.value)
        assertEquals(
            SettingsUiState(
                settings = AppSettings(),
                session = session,
                sessions = listOf(session),
                serverStatus = ServerStatus.NotConfigured,
                githubConfigured = true,
                cachedRole = ClassRole.EDITOR,
            ),
            model.uiState.value,
        )

        model.setPitchBlack(true)
        assertTrue(model.uiState.value.settings.pitchBlack)
    }

    @Test
    fun `nothing is published until the page is watched, and the developer row starts from the mode`() {
        developer.state.value = DeveloperState(revealed = true)
        val model = model()

        assertEquals(SettingsUiState(), model.uiState.value)
        assertTrue(model.developerRevealed.value)
    }

    @Test
    fun `the server is asked once at the start, and again only when the address changes`() {
        val model = model()
        assertEquals(1, sessions.asked)

        model.setPitchBlack(true)
        assertEquals(1, sessions.asked)

        model.setBaseUrl("https://other.example")
        assertEquals(2, sessions.asked)
    }

    @Test
    fun `a sync that fails says so on the page until the message is consumed`() = runTest {
        timetables.answer = SyncResult.Unauthorised
        val model = watched()

        model.refreshNow()
        assertEquals(SyncMessage.Unauthorised, model.uiState.value.message)
        assertFalse(model.uiState.value.isRefreshing)

        model.consumeMessage()
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `a bug report GitHub refuses writes the same message, and one report goes at a time`() = runTest {
        github.issueAnswer = IssueResult.Failed("refused")
        val gate = CompletableDeferred<Unit>().also { github.issueGate = it }
        val filed = mutableListOf<String>()
        val model = watched()

        model.fileIssue(DRAFT) { filed += it }
        model.fileIssue(DRAFT) { filed += it }
        assertTrue(model.uiState.value.isFilingIssue)
        assertEquals(1, github.issues.size)

        gate.complete(Unit)
        assertFalse(model.uiState.value.isFilingIssue)
        assertEquals(SyncMessage.IssueFailed, model.uiState.value.message)
        assertEquals(emptyList<String>(), filed)
    }

    @Test
    fun `each bug report that is filed hands its own page back and writes no message`() = runTest {
        val filed = mutableListOf<String>()
        val model = watched()

        model.fileIssue(DRAFT) { filed += "first $it" }
        model.fileIssue(DRAFT) { filed += "second $it" }

        assertEquals(listOf("first $ISSUE_URL", "second $ISSUE_URL"), filed)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `a check by hand reads the switch at the tap and raises the sheet whatever it finds`() = runTest {
        val model = watched()

        // Flipped after the view model was built, as the reader does it.
        settings.stored.value = settings.stored.value.copy(includePrerelease = true)
        model.checkForUpdates()
        assertEquals(listOf(true), updates.checks)
        assertEquals(UpdateCheck.UpToDate(null), model.uiState.value.update)
        assertTrue(model.uiState.value.showReleaseSheet)

        model.hideReleaseSheet()
        assertFalse(model.uiState.value.showReleaseSheet)

        // Over a check that has run, raising the sheet asks nothing more.
        model.showReleaseSheet()
        assertTrue(model.uiState.value.showReleaseSheet)
        assertEquals(listOf(true), updates.checks)

        model.dismissUpdate("v0.10.0")
        assertFalse(model.uiState.value.showReleaseSheet)
        assertEquals("v0.10.0", updates.dismissedTag.value)
    }

    @Test
    fun `the check at launch raises the sheet only for a release that is wanted and not put off`() = runTest {
        updates.answer = UpdateCheck.Available(RELEASE)
        updates.dismissedTag.value = RELEASE.tag
        val model = watched()

        model.checkForUpdatesAtLaunch()
        assertEquals(listOf(false), updates.checks)
        assertFalse(model.uiState.value.showReleaseSheet)

        updates.dismissedTag.value = null
        model.checkForUpdatesAtLaunch()
        assertTrue(model.uiState.value.showReleaseSheet)

        model.hideReleaseSheet()
        settings.stored.value = settings.stored.value.copy(autoCheckUpdates = false)
        model.checkForUpdatesAtLaunch()
        assertEquals(listOf(false, false), updates.checks)
        assertFalse(model.uiState.value.showReleaseSheet)
    }

    @Test
    fun `turning pre-releases on writes the switch, checks at once and raises the sheet`() = runTest {
        updates.answer = UpdateCheck.Available(RELEASE)
        val model = watched()

        model.enablePrereleases()

        assertTrue(settings.stored.value.includePrerelease)
        assertEquals(listOf(true), updates.checks)
        assertTrue(model.uiState.value.showReleaseSheet)
    }

    @Test
    fun `GitHub's account and sign-in are the page's, and a pull request that opens is shown once`() = runTest {
        val model = watched()
        github.account.value = GithubAccount("octocat", avatarUrl = null)
        github.flow.value = SIGNING_IN
        assertEquals(GithubAccount("octocat", avatarUrl = null), model.uiState.value.github)
        assertEquals(SIGNING_IN, model.uiState.value.signIn)

        model.signInWithGithub()
        assertEquals(1, github.signIns)

        github.pullAnswer = PullRequestResult.Opened(PULL_URL, refused = listOf("missing_key"))
        model.submitCorrections(listOf(CHANGE))
        assertEquals(TranslationSubmit.Opened(PULL_URL, listOf("missing_key")), model.translationSubmit.value)

        model.acknowledgeSubmission()
        assertEquals(TranslationSubmit.Idle, model.translationSubmit.value)

        github.pullAnswer = PullRequestResult.Failed("no fork")
        model.submitCorrections(listOf(CHANGE))
        assertEquals(TranslationSubmit.Failed, model.translationSubmit.value)
    }

    @Test
    fun `the Telegram link answers for the class on screen and resets when the class changes`() = runTest {
        val model = watched()

        model.refreshDeviceLink()
        assertEquals(DeviceLinkState.Ready(linkOf(ClassRole.ADMIN)), model.uiState.value.deviceLink)
        assertEquals(ClassRole.ADMIN, model.uiState.value.cachedRole)

        model.unlinkDevice()
        assertEquals(DeviceLinkState.Ready(linkOf(null)), model.uiState.value.deviceLink)
        assertEquals(listOf(1L to ClassRole.ADMIN, 1L to null), links.remembered)

        links.roles.value = links.roles.value + (2L to ClassRole.EDITOR)
        sessions.inner.rejoin(classId = 2L, className = "9Б")
        assertEquals(DeviceLinkState.Idle, model.uiState.value.deviceLink)
        assertEquals(ClassRole.EDITOR, model.uiState.value.cachedRole)
        assertEquals(ClassRole.EDITOR, model.uiState.value.effectiveRole)
    }

    @Test
    fun `an answer about the class switched away from is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>().also { links.gate = it }
        val model = watched()

        model.refreshDeviceLink()
        assertEquals(DeviceLinkState.Loading(null), model.uiState.value.deviceLink)

        sessions.inner.rejoin(classId = 2L, className = "9Б")
        gate.complete(Unit)

        assertEquals(DeviceLinkState.Idle, model.uiState.value.deviceLink)
        assertEquals(emptyList<Pair<Long, ClassRole?>>(), links.remembered)
    }

    @Test
    fun `work still out when the view model is cleared does not land`() {
        val gate = CompletableDeferred<Unit>().also { github.issueGate = it }
        val filed = mutableListOf<String>()
        val store = ViewModelStore()
        val model = ViewModelProvider(store, factoryOf(model()))[SettingsViewModel::class.java]

        model.fileIssue(DRAFT) { filed += it }
        store.clear()
        gate.complete(Unit)

        assertEquals(1, github.issues.size)
        assertEquals(emptyList<String>(), filed)
    }
}

private const val ISSUE_URL = "https://github.com/example/lessons/issues/7"
private const val PULL_URL = "https://github.com/example/lessons/pull/9"

private val DRAFT = IssueDraft(title = "It closes on opening", body = "Steps")

private val RELEASE = ReleaseInfo(
    tag = "v0.10.0",
    name = "v0.10.0",
    notes = "",
    htmlUrl = "https://github.com/example/lessons/releases/tag/v0.10.0",
    publishedAt = null,
    isPreRelease = false,
    apkUrl = null,
    apkBytes = null,
)

private val SIGNING_IN = DeviceFlow.AwaitingUser(
    userCode = "ABCD-1234",
    verificationUrl = "https://github.com/login/device",
    expiresAtMillis = 0L,
)

private val CHANGE = TranslationChange(
    paths = listOf("app/src/main/res/values/strings.xml"),
    key = "app_name",
    body = "Lessons",
)

/** Hands [model] to a store, the way the activity's provider would. */
private fun factoryOf(model: ViewModel) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
}

private fun linkOf(role: ClassRole?) = DeviceLink(
    deviceName = "Pixel",
    linked = role != null,
    role = role,
    canEdit = role != null,
    linkCode = null,
    botDeepLink = null,
)

/** [FakeSessionRepository], counting how often the server's status is asked for. */
private class CountingSessions(
    val inner: FakeSessionRepository = FakeSessionRepository(),
) : SessionRepository by inner {
    var asked = 0
        private set

    override suspend fun serverStatus(): ServerStatus {
        asked++
        return inner.serverStatus()
    }
}

/** A sync that answers [answer] at once. */
private class Timetables : TimetableRepository {
    var answer: SyncResult = SyncResult.Success
    override val timetable: Flow<Timetable?> = MutableStateFlow(null)
    override val syncedYears: Flow<Set<Int>> = MutableStateFlow(emptySet())
    override suspend fun snapshot(): Timetable? = null
    override suspend fun snapshotAroundToday(): Timetable? = null
    override suspend fun refresh(): SyncResult = answer
    override suspend fun refreshYear(openingYear: Int): SyncResult = answer
    override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit
}

/** Releases as a test sets them: every check finds [answer] and publishes it. */
private class Updates : UpdateRepository {
    var answer: UpdateCheck = UpdateCheck.UpToDate(null)
    val checks = mutableListOf<Boolean>()
    override val installedVersion: String = "0.9.0"
    override val state = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    override val dismissedTag = MutableStateFlow<String?>(null)

    override suspend fun check(includePrerelease: Boolean): UpdateCheck {
        checks += includePrerelease
        state.value = answer
        return answer
    }

    override suspend fun dismiss(tag: String) {
        dismissedTag.value = tag
    }

    override suspend fun lastCheckMillis(): Long? = null
}

/** GitHub as a test sets it; [issueGate], when set, holds a report until completed. */
private class Github : GithubRepository {
    var issueAnswer: IssueResult = IssueResult.Filed(ISSUE_URL)
    var issueGate: CompletableDeferred<Unit>? = null
    val issues = mutableListOf<IssueDraft>()
    var pullAnswer: PullRequestResult = PullRequestResult.Failed(null)
    var signIns = 0
    override val isConfigured = true
    override val account = MutableStateFlow<GithubAccount?>(null)
    override val flow = MutableStateFlow<DeviceFlow>(DeviceFlow.Idle)

    override fun signIn() {
        signIns++
    }

    override fun cancelSignIn() = Unit

    override suspend fun signOut() {
        account.value = null
    }

    override suspend fun fileIssue(draft: IssueDraft): IssueResult {
        issues += draft
        issueGate?.await()
        return issueAnswer
    }

    override suspend fun openTranslationPullRequest(changes: List<TranslationChange>): PullRequestResult =
        pullAnswer
}

/**
 * The Telegram link: [role] is what a refresh answers, [gate] holds one until
 * completed, and the roles are remembered per class, as the store keeps them.
 */
private class Links : DeviceLinkRepository {
    var role: ClassRole? = ClassRole.ADMIN
    var gate: CompletableDeferred<Unit>? = null
    val roles = MutableStateFlow<Map<Long, ClassRole?>>(emptyMap())
    val remembered = mutableListOf<Pair<Long, ClassRole?>>()
    override val link = MutableStateFlow<DeviceLink?>(null)

    override suspend fun refresh(): Result<DeviceLink> {
        gate?.await()
        return Result.success(linkOf(role))
    }

    override suspend fun unlink(): Result<DeviceLink> = Result.success(linkOf(null))

    override fun lastRole(classId: Long): Flow<ClassRole?> = roles.map { it[classId] }

    override suspend fun rememberRole(classId: Long, role: ClassRole?) {
        remembered += classId to role
        roles.value = roles.value + (classId to role)
    }
}

/** The developer mode, revealed or not; nothing here asks GitHub. */
private class Developer : DeveloperMode {
    override val state = MutableStateFlow(DeveloperState())

    override suspend fun reveal() {
        state.value = state.value.copy(revealed = true)
    }

    override suspend fun hide() = Unit
    override suspend fun verify() = Unit
    override suspend fun verifyIfStale() = Unit
    override suspend fun setTool(tool: DeveloperTool, on: Boolean) = Unit
}

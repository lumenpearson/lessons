package com.lumenpearson.lessons.core.data.developer

import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import com.lumenpearson.lessons.core.data.repository.PermissionsAnswer
import com.lumenpearson.lessons.core.data.repository.PullRequestResult
import com.lumenpearson.lessons.core.data.repository.TranslationChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer mode's rules over a store and a GitHub that are only memory
 * (#237): what a verification writes, what a failure to ask leaves standing,
 * when the daily re-check asks, and what hiding forgets.
 */
class DeveloperModeTest {

    private class MemoryStore : DeveloperStore {
        val state = MutableStateFlow(DeveloperStored())
        override val stored: Flow<DeveloperStored> = state
        override suspend fun update(transform: (DeveloperStored) -> DeveloperStored) {
            state.value = transform(state.value)
        }
    }

    private class FakeGithub(login: String?) : GithubRepository {
        val signedIn = MutableStateFlow(login?.let { GithubAccount(it, null) })
        var answer: PermissionsAnswer = PermissionsAnswer.Failed("not set")
        var asked = 0

        override val isConfigured = true
        override val account: Flow<GithubAccount?> = signedIn
        override val flow: StateFlow<DeviceFlow> = MutableStateFlow(DeviceFlow.Idle)
        override fun signIn() = Unit
        override fun cancelSignIn() = Unit
        override suspend fun signOut() {
            signedIn.value = null
        }
        override suspend fun fileIssue(draft: IssueDraft): IssueResult = IssueResult.Failed("unused")
        override suspend fun openTranslationPullRequest(changes: List<TranslationChange>): PullRequestResult =
            PullRequestResult.Failed("unused")
        override suspend fun repositoryPermissions(): PermissionsAnswer {
            asked++
            return answer
        }
    }

    private var clock = 1_000_000L
    private val store = MemoryStore()

    private fun TestScope.modeFor(github: FakeGithub): DeveloperModeImpl =
        DeveloperModeImpl(store, github, backgroundScope, now = { clock }).also { runCurrent() }

    private fun known(login: String, push: Boolean = false, admin: Boolean = false) =
        PermissionsAnswer.Known(login, RepositoryPermissions(admin = admin, push = push))

    @Test
    fun `a developer's chosen tools are in force once GitHub says push`() = runTest {
        val github = FakeGithub("dev").apply { answer = known("dev", push = true) }
        val mode = modeFor(github)
        mode.reveal()
        mode.setTool(DeveloperTool.NETWORK_LOG, on = true)
        runCurrent()
        assertEquals(emptySet<DeveloperTool>(), mode.state.value.tools)

        mode.verify()
        runCurrent()

        assertEquals(DeveloperAccess.Granted("dev", DeveloperRole.WRITE, clock), mode.state.value.access)
        assertEquals(setOf(DeveloperTool.NETWORK_LOG), mode.state.value.tools)
    }

    @Test
    fun `an account that can only read is refused, and its switches stay off`() = runTest {
        val github = FakeGithub("reader").apply { answer = known("reader") }
        val mode = modeFor(github)
        mode.reveal()
        mode.setTool(DeveloperTool.LAYOUT_GRID, on = true)

        mode.verify()
        runCurrent()

        assertEquals(DeveloperAccess.Denied("reader"), mode.state.value.access)
        assertTrue(mode.state.value.tools.isEmpty())
    }

    @Test
    fun `a failure to ask leaves a standing yes standing, and says why when none stands`() = runTest {
        val github = FakeGithub("dev").apply { answer = known("dev", admin = true) }
        val mode = modeFor(github)
        mode.verify()
        runCurrent()

        github.answer = PermissionsAnswer.Failed("IOException")
        mode.verify()
        runCurrent()
        assertTrue("GitHub down for an hour must not switch the tools off", mode.state.value.granted)

        store.state.value = store.state.value.copy(verdict = null)
        mode.verify()
        runCurrent()
        assertEquals(DeveloperAccess.Unknown("dev", "IOException"), mode.state.value.access)
    }

    @Test
    fun `a token GitHub no longer honours forgets the verdict`() = runTest {
        val github = FakeGithub("dev").apply { answer = known("dev", push = true) }
        val mode = modeFor(github)
        mode.verify()
        runCurrent()

        github.answer = PermissionsAnswer.SignedOut
        mode.verify()
        runCurrent()

        assertEquals(null, store.state.value.verdict)
        assertFalse(mode.state.value.granted)
    }

    @Test
    fun `another account signing in does not inherit the verdict`() = runTest {
        val github = FakeGithub("dev").apply { answer = known("dev", push = true) }
        val mode = modeFor(github)
        mode.verify()
        runCurrent()

        github.signedIn.value = GithubAccount("somebody", null)
        runCurrent()

        assertEquals(DeveloperAccess.Unknown("somebody", null), mode.state.value.access)
    }

    @Test
    fun `the daily re-check asks only once the section is found, and only when no verdict stands`() = runTest {
        val github = FakeGithub("dev").apply { answer = known("dev", push = true) }
        val mode = modeFor(github)

        mode.verifyIfStale()
        assertEquals("an install where nobody found the section never asks", 0, github.asked)

        mode.reveal()
        mode.verifyIfStale()
        assertEquals(1, github.asked)

        mode.verifyIfStale()
        assertEquals("a verdict from this morning stands", 1, github.asked)

        clock += VerdictLifetimeMillis + 1
        mode.verifyIfStale()
        assertEquals(2, github.asked)
    }

    @Test
    fun `nothing is asked of GitHub with nobody signed in`() = runTest {
        val github = FakeGithub(login = null)
        val mode = modeFor(github)
        mode.reveal()

        mode.verifyIfStale()

        assertEquals(0, github.asked)
        assertEquals(DeveloperAccess.SignedOut, mode.state.value.access)
    }

    @Test
    fun `hiding forgets the section, the verdict and the switches`() = runTest {
        val github = FakeGithub("dev").apply { answer = known("dev", push = true) }
        val mode = modeFor(github)
        mode.reveal()
        mode.setTool(DeveloperTool.ACTIVITY_LOG, on = true)
        mode.verify()
        runCurrent()

        mode.hide()
        runCurrent()

        assertEquals(DeveloperStored(), store.state.value)
        assertFalse(mode.state.value.revealed)
        assertTrue(mode.state.value.tools.isEmpty())
    }
}

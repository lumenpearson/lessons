package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.MeRemote
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLinkRepositoryTest {
    @Test
    fun `a refresh for A cannot publish after selecting B`() = assertLateDiscarded {
        it.selectSession(12)
    }

    @Test
    fun `a refresh cannot publish after the same class gets a replacement token`() = assertLateDiscarded {
        it.addSession(Session(7, "A", null, "synthetic-replacement"))
    }

    @Test
    fun `a refresh cannot publish an answer from the previous server`() = assertLateDiscarded {
        it.updateSettings { settings -> settings.copy(baseUrl = "https://other.invalid/") }
    }

    @Test
    fun `a late unlink cannot publish another class link code`() = assertLateDiscarded(unlink = true) {
        it.selectSession(12)
    }

    @Test
    fun `an IO failure retains the previous link and cancellation is never converted to Result`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        val previous = fixture.repository.refresh().getOrThrow()
        val offline = IOException("synthetic offline")
        fixture.api.onMe = { throw offline }
        assertSame(offline, fixture.repository.refresh().exceptionOrNull())
        assertEquals(previous, fixture.repository.link.value)
        val cancellation = CancellationException("screen closed")
        fixture.api.onMe = { throw cancellation }
        assertSame(cancellation, runCatching { fixture.repository.refresh() }.exceptionOrNull())
        assertEquals(previous, fixture.repository.link.value)
    }

    @Test
    fun `a role answer cannot be remembered after replacing its class bearer`() = lateRoleIsNotRemembered {
        it.addSession(Session(7, "Replacement", null, "synthetic-replacement"))
    }

    @Test
    fun `a role answer cannot be remembered after changing its server`() = lateRoleIsNotRemembered {
        it.updateSettings { settings -> settings.copy(baseUrl = "https://other.invalid/") }
    }

    @Test
    fun `a role answer cannot be remembered after selecting another class`() = lateRoleIsNotRemembered {
        it.selectSession(12)
    }

    @Test
    fun `an owned role answer is remembered and an older role cannot replace it`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.api.onMe = { DeviceLink("A", true, ClassRole.ADMIN, false, null, null) }
        fixture.repository.refresh().getOrThrow()
        fixture.repository.rememberRole(7, ClassRole.ADMIN)
        assertEquals(ClassRole.ADMIN, fixture.preferences.lastRole(7).first())
        fixture.repository.rememberRole(7, ClassRole.EDITOR)
        assertEquals(ClassRole.ADMIN, fixture.preferences.lastRole(7).first())
    }

    private fun lateRoleIsNotRemembered(change: suspend (LessonsPreferences) -> Unit) = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.api.onMe = { DeviceLink("A", true, ClassRole.ADMIN, false, null, null) }
        fixture.repository.refresh().getOrThrow()
        change(fixture.preferences)
        fixture.preferences.rememberRole(7, ClassRole.OWNER)
        fixture.repository.rememberRole(7, ClassRole.ADMIN)
        assertEquals(ClassRole.OWNER, fixture.preferences.lastRole(7).first())
    }

    private fun assertLateDiscarded(
        unlink: Boolean = false,
        change: suspend (LessonsPreferences) -> Unit,
    ) = runTest {
        val fixture = Fixture()
        fixture.seed()
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<DeviceLink>()
        fixture.api.onMe = { started.complete(Unit); answer.await() }
        val pending = async {
            if (unlink) fixture.repository.unlink() else fixture.repository.refresh()
        }
        started.await()
        try {
            val unlocked = fixture.preferences.membershipChanges.tryLock()
            if (unlocked) fixture.preferences.membershipChanges.unlock()
            assertTrue("Network I/O must not hold the membership gate", unlocked)
            change(fixture.preferences)
        } finally {
            answer.complete(DeviceLink("A", false, null, false, "A-CODE", null))
        }
        assertTrue("A response for an obsolete request must not become current", pending.await().isFailure)
        assertNull(fixture.repository.link.value)
        assertEquals(2, fixture.preferences.currentSessions().size)
    }

    private class Fixture {
        val dispatcher = UnconfinedTestDispatcher()
        val preferences = LessonsPreferences(MemoryStore(), testVault(), dispatcher)
        val api = ScriptedRemote()
        val repository = DeviceLinkRepositoryImpl(api, preferences, dispatcher)

        suspend fun seed() {
            preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
            preferences.addSession(Session(7, "A", null, "synthetic-A"))
            preferences.addSession(Session(12, "B", null, "synthetic-B"))
            preferences.selectSession(7)
        }
    }

    private class ScriptedRemote : MeRemote {
        var onMe: suspend () -> DeviceLink = { DeviceLink("A", true, null, false, null, null) }
        override suspend fun refresh(observation: CallObservation): DeviceLink = onMe()
        override suspend fun unlink(observation: CallObservation): DeviceLink = onMe()
    }
}

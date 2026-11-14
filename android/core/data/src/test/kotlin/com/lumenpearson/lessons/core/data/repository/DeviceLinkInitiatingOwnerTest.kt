package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.MeRemote
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class DeviceLinkInitiatingOwnerTest(private val change: String) {
    @Test
    fun `an unlink confirmation cannot target a membership captured after an IO delay`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
        preferences.addSession(Session(7, "A", null, "synthetic-A"))
        preferences.addSession(Session(12, "B", null, "synthetic-B"))
        preferences.selectSession(7)
        val remote = Links(preferences)
        val repository = DeviceLinkRepositoryImpl(remote, preferences, StandardTestDispatcher(testScheduler))
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            repository.unlink(ClassActionOwner(7, "https://school.invalid/", "synthetic-A"))
        }
        assertTrue(remote.unlinked.isEmpty())
        when (change) {
            "class" -> preferences.selectSession(12)
            "bearer" -> preferences.addSession(Session(7, "A", null, "synthetic-replacement"))
            "address" -> preferences.updateSettings { it.copy(baseUrl = "https://successor.invalid/") }
            else -> error("Unknown fixture change")
        }
        runCurrent()
        assertEquals(
            "The successor starts linked and must remain linked", emptyList<RequestCredentials>(), remote.unlinked,
        )
        assertTrue(pending.await().isFailure)
        assertNull(repository.link.value)
    }

    @Test
    fun `an unchanged initiating membership can unlink and obtain its own code`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
        preferences.addSession(Session(7, "A", null, "synthetic-A"))
        val remote = Links(preferences)
        val repository = DeviceLinkRepositoryImpl(remote, preferences, StandardTestDispatcher(testScheduler))
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            repository.unlink(ClassActionOwner(7, "https://school.invalid/", "synthetic-A"))
        }
        runCurrent()
        assertEquals("SYNTHETIC-CODE", pending.await().getOrThrow().linkCode)
        assertEquals(listOf("synthetic-A"), remote.unlinked.map { it.classToken })
    }

    private class Links(private val preferences: LessonsPreferences) : MeRemote {
        val unlinked = mutableListOf<RequestCredentials>()
        override suspend fun refresh(observation: CallObservation) = DeviceLink("Linked", true,
            ClassRole.ADMIN, true, null, null)

        override suspend fun unlink(observation: CallObservation): DeviceLink {
            val credentials = preferences.currentClassRequest().second
            observation.recordRequest(credentials)
            unlinked += credentials
            return DeviceLink(null, false, null, false, "SYNTHETIC-CODE", null)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String>> = listOf("class", "bearer", "address").map { arrayOf(it) }
    }
}

package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.ManageRemote
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class ManagementInitiatingOwnerTest(private val change: String, private val action: String) {
    @Test
    fun `a displayed class action cannot write to an identity selected while IO is queued`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
        preferences.addSession(Session(7, "Same name", null, "synthetic-A"))
        preferences.addSession(Session(12, "Same name", null, "synthetic-B"))
        preferences.selectSession(7)
        val owner = ClassActionOwner(7, "https://school.invalid/", "synthetic-A")
        val remote = Writes(preferences)
        val repository = ManageRepositoryImpl(
            remote, StandardTestDispatcher(testScheduler), ScheduleMembership(preferences) { false },
        ).forOwner(owner)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { perform(repository) }
        assertTrue("The IO boundary must actually delay the operation", remote.sent.isEmpty())

        when (change) {
            "class" -> preferences.selectSession(12)
            "bearer" -> preferences.addSession(Session(7, "Same name", null, "synthetic-replacement"))
            "address" -> preferences.updateSettings { it.copy(baseUrl = "https://successor.invalid/") }
            else -> error("Unknown fixture change")
        }
        runCurrent()

        assertEquals(
            "No successor may receive the displayed class's write", emptyList<RequestCredentials>(), remote.sent,
        )
        assertTrue(pending.await().isFailure)
        assertEquals(emptySet<String>(), remote.deletedTokens)
    }

    @Test
    fun `the same initiating owner can still complete the requested write`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
        preferences.addSession(Session(7, "Same name", null, "synthetic-A"))
        val remote = Writes(preferences)
        val repository = ManageRepositoryImpl(
            remote, StandardTestDispatcher(testScheduler), ScheduleMembership(preferences) { false },
        ).forOwner(ClassActionOwner(7, "https://school.invalid/", "synthetic-A"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { perform(repository) }
        runCurrent()
        assertTrue(pending.await().isSuccess)
        assertEquals(listOf("synthetic-A"), remote.sent.map { it.classToken })
        assertEquals(if (action == "delete") setOf("synthetic-A") else emptySet<String>(), remote.deletedTokens)
    }

    private suspend fun perform(repository: ManageRepository): Result<*> = when (action) {
        "mode" -> repository.setJoinMode(ClassJoinMode.INVITE)
        "delete" -> repository.deleteClass("Same name")
        else -> error("Unknown fixture action")
    }

    private class Writes(private val preferences: LessonsPreferences) : ManageRemote by UnusedManageRemote {
        val sent = mutableListOf<RequestCredentials>()
        val deletedTokens = mutableSetOf<String>()

        override suspend fun setJoinMode(mode: ClassJoinMode, observation: CallObservation): ManagedClass {
            val member = record(observation)
            return ManagedClass(member.classId, member.className, null, null, "Europe/Moscow", "MSK",
                "SYNTHETIC", mode, 1, 1, 0, null, true)
        }

        override suspend fun deleteClass(confirmName: String, observation: CallObservation) {
            val member = record(observation)
            check(confirmName == member.className)
            deletedTokens += member.token
        }

        private suspend fun record(observation: CallObservation): Session {
            val (member, credentials) = preferences.currentClassRequest()
            observation.recordRequest(credentials)
            sent += credentials
            return checkNotNull(member)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun cases(): List<Array<String>> = listOf("class", "bearer", "address").flatMap { boundary ->
            listOf("mode", "delete").map { arrayOf(boundary, it) }
        }
    }
}

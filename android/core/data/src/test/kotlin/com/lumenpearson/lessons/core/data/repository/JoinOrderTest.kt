package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.RestServerStatusRemote
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.v2.JoinRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinOrderTest {
    @Test
    fun `normalized join clears only the returned class before publishing its new token and callback`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault())
        preferences.addSession(Session(7, "Old", null, "old-token"))
        preferences.addSession(Session(12, "Other", null, "other-token"))
        val seen = mutableListOf<String>()
        val remote = JoinRemote { code, name ->
            assertEquals("DEMO24", code)
            assertEquals("Phone", name)
            seen += "remote"
            Session(7, "New", "School", "new-token")
        }
        val cache = object : TimetableCache {
            override suspend fun forgetClass(classId: Long) {
                seen += "wipe:$classId"
                assertEquals("old-token", preferences.currentSessions().single { it.classId == 7L }.token)
            }
            override suspend fun forgetEverything() = error("Joining never wipes other classes")
        }
        val repository = SessionRepositoryImpl(
            preferences, RestServerStatusRemote(NoCallsApi::warmup), cache, joinRemote = remote,
            ioDispatcher = UnconfinedTestDispatcher(), onActiveClassChanged = {
                assertEquals("new-token", preferences.currentSession()?.token)
                assertEquals("other-token", preferences.currentSessions().single { it.classId == 12L }.token)
                seen += "callback"
            })

        val actual = repository.join(" demo24 ", " Phone ").getOrThrow()

        assertEquals(Session(7, "New", "School", "new-token"), actual)
        assertEquals(listOf("remote", "wipe:7", "callback"), seen)
    }

    @Test
    fun `cancelling join preserves the original cancellation and existing memberships`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault())
        val old = Session(7, "Old", null, "old-token")
        preferences.addSession(old)
        val cancelled = CancellationException("synthetic")
        val cache = object : TimetableCache {
            override suspend fun forgetClass(classId: Long) = error("No wipe before success")
            override suspend fun forgetEverything() = error("No wipe before success")
        }
        val repository = SessionRepositoryImpl(
            preferences, RestServerStatusRemote(NoCallsApi::warmup), cache,
            joinRemote = JoinRemote { _, _ -> throw cancelled },
            ioDispatcher = UnconfinedTestDispatcher(), onActiveClassChanged = { error("No callback") })

        assertSame(cancelled, runCatching { repository.join("demo24", null) }.exceptionOrNull())
        assertEquals(listOf(old), preferences.currentSessions())
    }

    private object NoCallsApi : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("No v1 join")
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?) = error("Unused")
        override suspend fun warmup() = error("Unused")
        override suspend fun health() = error("Unused")
        override suspend fun me() = error("Unused")
        override suspend fun unlink() = error("Unused")
    }
}

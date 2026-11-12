package com.lumenpearson.lessons.core.data.repository

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.V2BundleTagStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.v2.JoinRemote
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.RemoteError
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemote
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemoteResult
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V2InFlightMembershipTest {
    @Test
    fun `a delayed invalid token removes its sent membership rather than the newly selected class`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.block = true
        val other = fixture.dao.snapshot(12)
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.sessions.select(12)
        fixture.answer.completeExceptionally(invalidToken())
        result.await()
        assertEquals(listOf(12L), fixture.preferences.currentSessions().map { it.classId })
        assertEquals(12L, fixture.sessions.current()?.classId)
        assertNull(fixture.dao.snapshot(7))
        assertEquals(other, fixture.dao.snapshot(12))
        fixture.assertNoTags(7)
        assertEquals("v2:12", fixture.preferences.v2BundleTag("12|2026"))
    }

    @Test
    fun `a delayed invalid token cannot revoke a replacement token for the same class`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.block = true
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.sessions.join("SYNTHETIC", null).getOrThrow()
        fixture.answer.completeExceptionally(invalidToken())
        result.await()
        assertEquals("synthetic-replacement", fixture.sessions.current()?.token)
        assertEquals(setOf(7L, 12L), fixture.preferences.currentSessions().map { it.classId }.toSet())
        assertNull(fixture.dao.snapshot(7))
        fixture.assertNoTags(7)
    }

    @Test
    fun `a delayed changed response cannot recreate a class after leave completes`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.block = true
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.sessions.leave(7)
        fixture.answer.complete(changed(7))
        assertTrue(result.await() is SyncResult.Failed)
        assertNull(fixture.dao.snapshot(7))
        fixture.assertNoTags(7)
        assertEquals(listOf(12L), fixture.preferences.currentSessions().map { it.classId })
    }

    @Test
    fun `a delayed changed response cannot recreate rows after sign out completes`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.block = true
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.sessions.signOut()
        fixture.answer.complete(changed(7))
        assertTrue(result.await() is SyncResult.Failed)
        assertTrue(fixture.preferences.currentSessions().isEmpty())
        assertNull(fixture.dao.snapshot(7))
        assertNull(fixture.dao.snapshot(12))
        fixture.assertNoTags(7)
        fixture.assertNoTags(12)
    }

    @Test
    fun `a switch before send refuses the obsolete conditional request without cross class tags`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        val beforeA = fixture.dao.snapshot(7)
        val beforeB = fixture.dao.snapshot(12)
        fixture.block = true
        fixture.beforeSend = { fixture.sessions.select(12) }
        fixture.answer.complete(changed(12, "late-b"))
        assertTrue(fixture.timetable.refreshYear(2026) is SyncResult.Failed)
        assertEquals(beforeA, fixture.dao.snapshot(7))
        assertEquals(beforeB, fixture.dao.snapshot(12))
        assertEquals("v2:7", fixture.preferences.v2BundleTag("7|2026"))
        assertEquals("v2:12", fixture.preferences.v2BundleTag("12|2026"))
    }

    @Test
    fun `leaving waits for an applying result and then clears its rows and both tag generations`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.holdTagWrite = true
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.tagWriteStarted.await()
        val leaving = async(start = CoroutineStart.UNDISPATCHED) { fixture.sessions.leave(7) }
        try {
            assertFalse("an exit must not finish halfway through the result transaction", leaving.isCompleted)
        } finally {
            fixture.releaseTagWrite.complete(Unit)
        }
        result.await()
        leaving.await()
        assertNull(fixture.dao.snapshot(7))
        fixture.assertNoTags(7)
        assertEquals(listOf(12L), fixture.preferences.currentSessions().map { it.classId })
    }

    @Test
    fun `replacement waits for an applying result before wiping the old token cache`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.holdTagWrite = true
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.tagWriteStarted.await()
        val joining = async(start = CoroutineStart.UNDISPATCHED) { fixture.sessions.join("SYNTHETIC", null) }
        try {
            assertFalse("replacement cannot overtake a partially applied old response", joining.isCompleted)
        } finally {
            fixture.releaseTagWrite.complete(Unit)
        }
        result.await()
        joining.await().getOrThrow()
        assertEquals("synthetic-replacement", fixture.sessions.current()?.token)
        assertNull(fixture.dao.snapshot(7))
        fixture.assertNoTags(7)
    }

    @Test
    fun `a delayed refusal from another server cannot revoke the retained membership`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.block = true
        val before = fixture.preferences.currentSessions()
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.preferences.updateSettings { it.copy(baseUrl = "https://other.invalid/") }
        fixture.answer.completeExceptionally(invalidToken())
        result.await()
        assertEquals(before, fixture.preferences.currentSessions())
        assertEquals("v2:7", fixture.preferences.v2BundleTag("7|2026"))
    }

    @Test
    fun `a delayed changed response from another server does not replace the cache`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.block = true
        val before = fixture.dao.snapshot(7)
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.preferences.updateSettings { it.copy(baseUrl = "https://other.invalid/") }
        fixture.answer.complete(changed(7, "other-server"))
        assertTrue(result.await() is SyncResult.Failed)
        assertEquals(before, fixture.dao.snapshot(7))
        assertEquals("v2:7", fixture.preferences.v2BundleTag("7|2026"))
    }

    internal class Fixture {
        private val store = MemoryStore()
        private val vault = testVault()
        val preferences = LessonsPreferences(store, vault, UnconfinedTestDispatcher())
        val dao = InMemoryTimetableDao()
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<ScheduleRemoteResult>()
        val tagWriteStarted = CompletableDeferred<Unit>()
        val releaseTagWrite = CompletableDeferred<Unit>()
        var block = false
        var holdTagWrite = false
        var beforeSend: suspend () -> Unit = {}
        val sent = mutableListOf<RequestCredentials>()
        private val realTags = V2BundleTagStore(preferences)
        private val tags = object : BundleTagStore by realTags {
            override suspend fun remember(signature: String, etag: String) {
                if (holdTagWrite) {
                    tagWriteStarted.complete(Unit)
                    releaseTagWrite.await()
                }
                realTags.remember(signature, etag)
            }
        }
        private val remote = object : ScheduleRemote {
            override suspend fun window(openingYear: Int, ifNoneMatch: String?): ScheduleRemoteResult =
                window(openingYear, ifNoneMatch, CallObservation())

            override suspend fun window(
                openingYear: Int,
                ifNoneMatch: String?,
                observation: CallObservation,
            ): ScheduleRemoteResult {
                if (block) beforeSend()
                observation.recordRequest(preferences.credentials.refresh())
                return if (block) {
                    sent += checkNotNull(observation.credentials)
                    started.complete(Unit)
                    answer.await()
                } else {
                    changed(checkNotNull(preferences.currentSession()).classId)
                }
            }
        }
        val timetable: TimetableRepositoryImpl = TimetableRepositoryImpl(
            dao, remote, preferences.session.map { it?.classId },
            clock = Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneOffset.UTC),
            ioDispatcher = UnconfinedTestDispatcher(), bundleTags = tags,
            membership = ScheduleMembership(preferences) { sessions.rejectRequest(it) })
        val sessions: SessionRepositoryImpl = SessionRepositoryImpl(preferences, NoCalls, timetable,
            JoinRemote { _, _ -> Session(7, "Class 7", null, "synthetic-replacement") },
            ioDispatcher = UnconfinedTestDispatcher())

        suspend fun seed() {
            preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
            for (id in listOf(12L, 7L)) {
                preferences.addSession(Session(id, "Class $id", null, "synthetic-$id"))
                preferences.writeBundleTag("$id|2026", "v1:$id")
                assertEquals(SyncResult.Success, timetable.refreshYear(2026))
            }
        }

        suspend fun assertNoTags(classId: Long) {
            assertNull(preferences.bundleTag("$classId|2026"))
            assertNull(preferences.v2BundleTag("$classId|2026"))
        }

        fun anotherPreferences(): LessonsPreferences = LessonsPreferences(store, vault, UnconfinedTestDispatcher())
    }

    private object NoCalls : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("No v1 join")
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?) = error("No v1 schedule")
        override suspend fun warmup() = error("Unused")
        override suspend fun health() = error("Unused")
        override suspend fun me() = error("Unused")
        override suspend fun unlink() = error("Unused")
    }

    private companion object {
        fun invalidToken() = RemoteError(Code.UNAUTHENTICATED, "DEVICE_TOKEN_INVALID", httpStatus = 401)

        fun changed(id: Long, tag: String = "v2:$id"): ScheduleRemoteResult.Changed {
            val date = LocalDate.of(2026, 11, 12)
            return ScheduleRemoteResult.Changed(Timetable(SchoolClassInfo(id, "Class $id"),
                listOf(SchoolDay(date, date.dayOfWeek.value)), syncedAtEpochMillis = 1L), tag)
        }
    }
}

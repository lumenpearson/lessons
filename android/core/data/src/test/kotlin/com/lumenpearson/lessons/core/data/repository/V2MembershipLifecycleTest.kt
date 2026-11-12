package com.lumenpearson.lessons.core.data.repository

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.V2BundleTagStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.v2.JoinRemote
import com.lumenpearson.lessons.core.data.network.v2.RemoteError
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemote
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemoteResult
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V2MembershipLifecycleTest {
    @Test
    fun `only the invalid token reason removes a real membership and its cache and both tag generations`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        val members = fixture.preferences.currentSessions()
        val before = fixture.dao.snapshot(7)
        val other = fixture.dao.snapshot(12)
        val failures = listOf(
            IOException("synthetic offline"),
            RemoteError(Code.UNAUTHENTICATED, httpStatus = 401),
            RemoteError(Code.FAILED_PRECONDITION, "CLIENT_TOO_OLD", httpStatus = 400),
            RemoteError(Code.PERMISSION_DENIED, "FUTURE_REASON", httpStatus = 403),
        )
        for (failure in failures) {
            fixture.failure = failure
            val actual = fixture.timetable.refreshYear(2026)
            if ((failure as? RemoteError)?.reason == "CLIENT_TOO_OLD") assertEquals(SyncResult.ClientTooOld, actual)
            else assertTrue(actual is SyncResult.Failed)
            assertEquals(members, fixture.preferences.currentSessions())
            assertEquals(before, fixture.dao.snapshot(7))
            assertEquals(other, fixture.dao.snapshot(12))
            fixture.assertTags(7, present = true)
            fixture.assertTags(12, present = true)
        }

        fixture.failure = RemoteError(Code.UNAUTHENTICATED, "DEVICE_TOKEN_INVALID", httpStatus = 200)
        assertEquals(SyncResult.Unauthorised, fixture.timetable.refreshYear(2026))
        assertEquals(listOf(12L), fixture.preferences.currentSessions().map { it.classId })
        assertNull(fixture.dao.snapshot(7))
        assertEquals(other, fixture.dao.snapshot(12))
        fixture.assertTags(7, present = false)
        fixture.assertTags(12, present = true)
    }

    @Test
    fun `leaving and signing out use the same actual cache adapter as sync`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        fixture.sessions.leave(7)
        assertEquals(listOf(12L), fixture.preferences.currentSessions().map { it.classId })
        assertNull(fixture.dao.snapshot(7))
        fixture.assertTags(7, present = false)
        fixture.assertTags(12, present = true)
        fixture.sessions.signOut()
        assertTrue(fixture.preferences.currentSessions().isEmpty())
        assertNull(fixture.dao.snapshot(12))
        fixture.assertTags(12, present = false)
    }

    @Test
    fun `year eviction clears both tag generations without touching another class`() = runTest {
        val fixture = Fixture()
        fixture.seed()
        for (year in listOf(2024, 2023)) {
            fixture.preferences.writeBundleTag("7|$year", "v1:7|$year")
            assertEquals(SyncResult.Success, fixture.timetable.refreshYear(year))
        }
        assertEquals(setOf(2024, 2025, 2026), fixture.dao.windows(7).map { it.openingYear }.toSet())
        assertNull(fixture.preferences.bundleTag("7|2023"))
        assertNull(fixture.preferences.v2BundleTag("7|2023"))
        fixture.assertTags(7, present = true)
        fixture.assertTags(12, present = true)
    }

    private class Fixture {
        val preferences = LessonsPreferences(MemoryStore(), testVault())
        val dao = InMemoryTimetableDao()
        var failure: Exception? = null
        private val tagsSeen = mutableListOf<String?>()
        private val clock = Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneOffset.UTC)
        private val remote = ScheduleRemote { year, tag ->
            tagsSeen += tag
            failure?.let { throw it }
            val member = checkNotNull(preferences.currentSession())
            val date = LocalDate.of(year, 11, 12)
            ScheduleRemoteResult.Changed(Timetable(SchoolClassInfo(member.classId, member.className),
                listOf(SchoolDay(date, date.dayOfWeek.value)), syncedAtEpochMillis = clock.millis()),
                "v2:${member.classId}|$year")
        }
        val timetable = TimetableRepositoryImpl(dao, remote, preferences.session.map { it?.classId },
            clock = clock, ioDispatcher = UnconfinedTestDispatcher(), bundleTags = V2BundleTagStore(preferences),
            onTokenRejected = { sessions.leaveActive() })
        val sessions: SessionRepository = SessionRepositoryImpl(preferences, NoCallsApi, timetable,
            joinRemote = JoinRemote { _, _ -> error("No join") }, ioDispatcher = UnconfinedTestDispatcher())

        suspend fun seed() {
            for (id in listOf(12L, 7L)) {
                preferences.addSession(Session(id, "Class $id", null, "synthetic-$id"))
                for (year in listOf(2025, 2026)) {
                    preferences.writeBundleTag("$id|$year", "v1:$id|$year")
                    assertEquals(SyncResult.Success, timetable.refreshYear(year))
                }
            }
            assertTrue(tagsSeen.all { it == null })
        }

        suspend fun assertTags(classId: Long, present: Boolean) {
            for (year in listOf(2025, 2026)) {
                assertEquals(if (present) "v1:$classId|$year" else null, preferences.bundleTag("$classId|$year"))
                assertEquals(if (present) "v2:$classId|$year" else null, preferences.v2BundleTag("$classId|$year"))
            }
        }
    }

    private object NoCallsApi : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("No v1 join")
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?) = error("No v1 window")
        override suspend fun warmup() = error("Unused")
        override suspend fun health() = error("Unused")
        override suspend fun me() = error("Unused")
        override suspend fun unlink() = error("Unused")
    }
}

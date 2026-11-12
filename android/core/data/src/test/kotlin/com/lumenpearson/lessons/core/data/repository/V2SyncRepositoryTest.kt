package com.lumenpearson.lessons.core.data.repository

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V2SyncRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneOffset.UTC)
    private fun window(id: Long = 7, zone: String = "Europe/Moscow") = Timetable(
        SchoolClassInfo(id, "9A", timeZoneId = zone),
        days = listOf(SchoolDay(LocalDate.parse("2026-11-12"), 4)), syncedAtEpochMillis = clock.millis())

    @Test
    fun `confirmed class rows land before their own tag and exactly one notification`() = runTest {
        val dao = InMemoryTimetableDao()
        val order = mutableListOf<String>()
        val tags = object : BundleTagStore by RecordingTagStore() {
            override suspend fun remember(signature: String, etag: String) {
                assertNotNull(dao.snapshot(17))
                assertEquals(2026, dao.windows(17).single().openingYear)
                order += "tag:$signature:$etag"
            }
        }
        val repository = repository(dao, ScheduleRemote { year, _ ->
            assertEquals(2026, year)
            ScheduleRemoteResult.Changed(window(17), "new-tag")
        }, tags, onData = { order += "notify" })

        assertEquals(SyncResult.Success, repository.refreshYear(2026))
        assertEquals(listOf("tag:17|2026:new-tag", "notify"), order)
        assertEquals(17L, checkNotNull(dao.snapshot(17)).schoolClass.id)
    }

    @Test
    fun `missing rows behind a stored tag cause exactly one unconditional retry`() = runTest {
        val tags = RecordingTagStore().apply { written["7|2026"] = "old" }
        val seen = mutableListOf<String?>()
        var data = 0
        var unchanged = 0
        val repository = repository(InMemoryTimetableDao(), ScheduleRemote { _, tag ->
            seen += tag
            if (tag != null) ScheduleRemoteResult.NotModified("old") else ScheduleRemoteResult.Changed(window(), "new")
        }, tags, onData = { data++ }, onUnchanged = { unchanged++ })

        assertEquals(SyncResult.Success, repository.refreshYear(2026))
        assertEquals(listOf("old", null), seen)
        assertEquals("new", tags.written["7|2026"])
        assertEquals(1, data)
        assertEquals(0, unchanged)
    }

    @Test
    fun `not modified keeps the actual cache and uses only the unchanged callback`() = runTest {
        val tags = RecordingTagStore()
        var data = 0
        var unchanged = 0
        var answer: ScheduleRemoteResult = ScheduleRemoteResult.Changed(window(), "same")
        val repository = repository(InMemoryTimetableDao(), ScheduleRemote { _, _ -> answer }, tags,
            onData = { data++ }, onUnchanged = { unchanged++ })
        assertEquals(SyncResult.Success, repository.refreshYear(2026))
        val before = repository.snapshot()
        answer = ScheduleRemoteResult.NotModified("same")
        assertEquals(SyncResult.Success, repository.refreshYear(2026))
        assertEquals(before, repository.snapshot())
        assertEquals(1, data)
        assertEquals(1, unchanged)
    }

    @Test
    fun `only DEVICE_TOKEN_INVALID rejects membership while other refusals retain the cache and tag`() = runTest {
        val tags = RecordingTagStore()
        var failure: RemoteError? = null
        var rejected = 0
        val repository = repository(InMemoryTimetableDao(), ScheduleRemote { _, _ ->
            failure?.let { throw it }
            ScheduleRemoteResult.Changed(window(), "same")
        }, tags, onRejected = { rejected++ })
        assertEquals(SyncResult.Success, repository.refreshYear(2026))
        val before = repository.snapshot()
        for (reason in listOf(null, "CLIENT_TOO_OLD", "FUTURE_REASON")) {
            failure = RemoteError(Code.UNAUTHENTICATED, reason, httpStatus = 401)
            val actual = repository.refreshYear(2026)
            if (reason == "CLIENT_TOO_OLD") assertEquals(SyncResult.ClientTooOld, actual)
            else assertTrue(actual is SyncResult.Failed)
            assertEquals(0, rejected)
            assertEquals(before, repository.snapshot())
            assertEquals("same", tags.written["7|2026"])
        }
        failure = RemoteError(Code.UNAUTHENTICATED, "DEVICE_TOKEN_INVALID", httpStatus = 401)
        assertEquals(SyncResult.Unauthorised, repository.refreshYear(2026))
        assertEquals(1, rejected)
    }

    @Test
    fun `a cancelled remote keeps its original cancellation and does not publish data`() = runTest {
        val original = CancellationException("synthetic")
        val repository = repository(InMemoryTimetableDao(), ScheduleRemote { _, _ -> throw original },
            onData = { error("No success callback") }, onRejected = { error("No rejection callback") })
        assertSame(original, runCatching { repository.refreshYear(2026) }.exceptionOrNull())
    }

    @Test
    fun `the cached school's zone decides which year is requested at a summer boundary`() = runTest {
        val seen = mutableListOf<Int>()
        val repository = repository(InMemoryTimetableDao(), ScheduleRemote { year, _ ->
            seen += year
            ScheduleRemoteResult.Changed(window(zone = "Asia/Yakutsk"), null)
        }, at = Clock.fixed(Instant.parse("2026-05-31T18:00:00Z"), ZoneOffset.UTC))
        assertEquals(SyncResult.Success, repository.refresh())
        assertEquals(SyncResult.Success, repository.refresh())
        assertEquals(listOf(2025, 2026), seen)
    }

    private fun repository(dao: InMemoryTimetableDao, remote: ScheduleRemote,
        tags: BundleTagStore = BundleTagStore.None, onData: () -> Unit = {}, onUnchanged: () -> Unit = {},
        onRejected: suspend () -> Unit = {}, at: Clock = clock) = TimetableRepositoryImpl(
        dao, remote, activeClassId = flowOf(7L), clock = at, ioDispatcher = UnconfinedTestDispatcher(),
        bundleTags = tags, onDataChanged = onData, onNothingChanged = onUnchanged, onTokenRejected = onRejected)
}

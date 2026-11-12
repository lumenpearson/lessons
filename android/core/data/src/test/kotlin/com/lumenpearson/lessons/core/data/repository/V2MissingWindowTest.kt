package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemote
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemoteResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V2MissingWindowTest {
    @Test
    fun `a second not modified without cached rows fails without freshness callbacks or a new tag`() = runTest {
        val dao = InMemoryTimetableDao()
        val tags = RecordingTagStore().apply { written["7|2026"] = "old-tag" }
        val requestedTags = mutableListOf<String?>()
        var changed = 0
        var unchanged = 0
        val repository = TimetableRepositoryImpl(dao, ScheduleRemote { _, tag ->
            requestedTags += tag
            ScheduleRemoteResult.NotModified("unusable-tag")
        }, flowOf(7L), ioDispatcher = UnconfinedTestDispatcher(), bundleTags = tags,
            onDataChanged = { changed += 1 }, onNothingChanged = { unchanged += 1 })

        val result = repository.refreshYear(2026)

        assertTrue("no schedule window was recovered", result is SyncResult.Failed)
        assertEquals(listOf("old-tag", null), requestedTags)
        assertEquals(0, changed)
        assertEquals(0, unchanged)
        assertNull(dao.snapshot(7))
        assertTrue(dao.windows(7).isEmpty())
        assertEquals(mapOf("7|2026" to "old-tag"), tags.written)
    }
}

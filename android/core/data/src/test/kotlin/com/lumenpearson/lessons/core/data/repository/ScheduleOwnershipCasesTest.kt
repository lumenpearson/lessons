package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemoteResult
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.Timetable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleOwnershipCasesTest {
    @Test
    fun `a still joined inactive class may finish its own request without touching the selected class`() = runTest {
        val fixture = V2InFlightMembershipTest.Fixture()
        fixture.seed()
        val selected = fixture.dao.snapshot(12)
        fixture.block = true
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.sessions.select(12)
        fixture.answer.complete(changed("fresh-a"))
        assertEquals(SyncResult.Success, result.await())
        assertEquals(12L, fixture.sessions.current()?.classId)
        assertEquals(selected, fixture.dao.snapshot(12))
        assertEquals("fresh-a", fixture.preferences.v2BundleTag("7|2026"))
        assertEquals("v2:12", fixture.preferences.v2BundleTag("12|2026"))
    }

    @Test
    fun `a replacement token starts a fresh flight instead of joining the old token request`() = runTest {
        val fixture = V2InFlightMembershipTest.Fixture()
        fixture.seed()
        fixture.block = true
        val old = async { fixture.timetable.refreshYear(2026) }
        fixture.started.await()
        fixture.sessions.join("SYNTHETIC", null).getOrThrow()
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { fixture.timetable.refreshYear(2026) }
        try {
            assertEquals(listOf("synthetic-7", "synthetic-replacement"), fixture.sent.map { it.classToken })
        } finally {
            fixture.answer.complete(changed("fresh-token"))
        }
        assertTrue(old.await() is SyncResult.Failed)
        assertEquals(SyncResult.Success, fresh.await())
        assertEquals("synthetic-replacement", fixture.sessions.current()?.token)
        assertEquals("fresh-token", fixture.preferences.v2BundleTag("7|2026"))
    }

    @Test
    fun `another preferences facade cannot change the server halfway through result application`() = runTest {
        val fixture = V2InFlightMembershipTest.Fixture()
        fixture.seed()
        fixture.holdTagWrite = true
        val result = async { fixture.timetable.refreshYear(2026) }
        fixture.tagWriteStarted.await()
        val other = fixture.anotherPreferences()
        val setting = async(start = CoroutineStart.UNDISPATCHED) {
            other.updateSettings { it.copy(baseUrl = "https://other.invalid/") }
        }
        try {
            assertFalse("facades of one store must share the persistence gate", setting.isCompleted)
        } finally {
            fixture.releaseTagWrite.complete(Unit)
        }
        assertEquals(SyncResult.Success, result.await())
        setting.await()
        assertEquals("https://other.invalid/", fixture.preferences.currentSettings().baseUrl)
    }

    private fun changed(tag: String) = ScheduleRemoteResult.Changed(
        Timetable(SchoolClassInfo(7, "Class 7"), emptyList(), syncedAtEpochMillis = 2L), tag,
    )
}

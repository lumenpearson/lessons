package com.lumenpearson.lessons.core.data.datastore

import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the store keeps beside the settings for the sync's sake rather than the
 * reader's: an `ETag` per window, the shape of the last schedule, and the role
 * last given in each class.
 *
 * Most of it is found by key prefix when it is swept, which is where it goes
 * wrong without a sound: a class's prefix without its trailing separator takes
 * class 12's tags with class 1's, and the cost is a full download nobody sees.
 * docs/history.md records that sweep as written and never run, from when the
 * store needed a `Context`; the in-memory store reaches it now, and these hold
 * it still while it moves into `BundleTags`.
 */
class BundleTagsAndRolesTest {

    private val preferences = LessonsPreferences(MemoryStore(), testVault())

    @Test
    fun `a tag is read back under its own signature and no other`() = runBlocking {
        preferences.writeBundleTag("1|2026", "etag-a")

        assertEquals("etag-a", preferences.bundleTag("1|2026"))
        assertNull(preferences.bundleTag("1|2025"))

        preferences.forgetBundleTag("1|2026")
        assertNull(preferences.bundleTag("1|2026"))
    }

    @Test
    fun `forgetting one class's tags spares the classes whose ids begin with its digits`() = runBlocking {
        listOf("1|2025", "1|2026", "12|2026", "100|2026").forEach { preferences.writeBundleTag(it, "tag $it") }

        preferences.forgetBundleTagsOf(1)

        assertNull(preferences.bundleTag("1|2025"))
        assertNull(preferences.bundleTag("1|2026"))
        assertEquals("tag 12|2026", preferences.bundleTag("12|2026"))
        assertEquals("tag 100|2026", preferences.bundleTag("100|2026"))
    }

    @Test
    fun `forgetting the tags outside a set spares that set, and nothing else is a tag`() = runBlocking {
        listOf("1|2026", "12|2026", "100|2026").forEach { preferences.writeBundleTag(it, "tag $it") }
        preferences.writeScheduleFingerprint("shape")
        preferences.rememberRole(1, ClassRole.ADMIN)

        preferences.forgetBundleTagsOutside(listOf(12L))

        assertNull(preferences.bundleTag("1|2026"))
        assertEquals("tag 12|2026", preferences.bundleTag("12|2026"))
        assertNull(preferences.bundleTag("100|2026"))

        preferences.forgetBundleTagsOutside(emptyList())

        assertNull(preferences.bundleTag("12|2026"))
        assertEquals("shape", preferences.scheduleFingerprint())
        assertEquals(ClassRole.ADMIN, preferences.lastRole(1).first())
    }

    @Test
    fun `the schedule's shape is the one written last, and a switch of class forgets it`() = runBlocking {
        preferences.addSession(session(1))
        preferences.addSession(session(2))
        assertNull(preferences.scheduleFingerprint())

        preferences.writeScheduleFingerprint("first")
        preferences.writeScheduleFingerprint("second")
        assertEquals("second", preferences.scheduleFingerprint())

        preferences.selectSession(1)
        assertNull(preferences.scheduleFingerprint())
    }

    @Test
    fun `a role is kept per class, forgotten with null, and gone after a sign-out`() = runBlocking {
        preferences.writeBundleTag("9|2026", "kept")
        preferences.rememberRole(7, ClassRole.ADMIN)
        preferences.rememberRole(9, ClassRole.EDITOR)

        preferences.rememberRole(7, null)

        assertNull(preferences.lastRole(7).first())
        assertEquals(ClassRole.EDITOR, preferences.lastRole(9).first())

        preferences.clearSession()

        assertNull(preferences.lastRole(9).first())
        assertEquals("kept", preferences.bundleTag("9|2026"))
    }

    private fun session(classId: Long) =
        Session(classId = classId, className = "Class $classId", school = null, token = "token-$classId")
}

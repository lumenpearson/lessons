package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The classes this phone belongs to, as they survive being written down.
 *
 * Two of these are about upgrades rather than about features, and they are the
 * reason this file exists. Every install made before the membership list held
 * its one class in four flat keys; if the new read path does not find it, that
 * user opens the app to the join screen with no class and no explanation, and
 * the onboarding flag — which is inferred from the same keys — promises them
 * four screens of introduction they last saw in September. Neither shows up in
 * a screenshot or a log, and neither is reachable through the public API, so
 * this is the only place they can be pinned.
 */
class MembershipsTest {

    private fun session(id: Long, name: String, token: String = "t$id") =
        Session(classId = id, className = name, school = null, token = token)

    // -- the upgrade path ---------------------------------------------------

    @Test
    fun `an install from before the list still finds its class`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.TOKEN to "old-token",
            MembershipKeys.CLASS_ID to 7L,
            MembershipKeys.CLASS_NAME to "7А",
            MembershipKeys.SCHOOL to "Школа 1",
        )

        assertEquals(
            listOf(Session(classId = 7, className = "7А", school = "Школа 1", token = "old-token")),
            prefs.memberships(),
        )
        assertEquals(7L, prefs.activeMembership()?.classId)
    }

    @Test
    fun `writing the list retires the keys it replaced`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.TOKEN to "old-token",
            MembershipKeys.CLASS_ID to 7L,
            MembershipKeys.CLASS_NAME to "7А",
        )

        prefs.writeMemberships(prefs.memberships())

        assertNull(prefs[MembershipKeys.TOKEN])
        assertNull(prefs[MembershipKeys.CLASS_ID])
        assertNull(prefs[MembershipKeys.CLASS_NAME])
        // …and the class itself is still there, which is the whole point of
        // removing them only once it has been written somewhere else.
        assertEquals(listOf(7L), prefs.memberships().map { it.classId })
    }

    @Test
    fun `a phone in no class reads as being in none`() {
        val prefs = mutablePreferencesOf()

        assertEquals(emptyList<Session>(), prefs.memberships())
        assertNull(prefs.activeMembership())
    }

    // -- the list -----------------------------------------------------------

    @Test
    fun `memberships survive a round trip in order`() {
        val prefs = mutablePreferencesOf()
        val classes = listOf(session(1, "7А"), session(2, "9Б"), session(3, "11В"))

        prefs.writeMemberships(classes)

        assertEquals(classes, prefs.memberships())
    }

    @Test
    fun `re-joining a class replaces its token where it stands`() {
        val classes = listOf(session(1, "7А"), session(2, "9Б"), session(3, "11В"))

        val merged = classes.withMembership(session(2, "9Б", token = "fresh"))

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.classId })
        assertEquals("fresh", merged.single { it.classId == 2L }.token)
    }

    @Test
    fun `joining a new class appends it`() {
        val merged = listOf(session(1, "7А")).withMembership(session(2, "9Б"))

        assertEquals(listOf(1L, 2L), merged.map { it.classId })
    }

    // -- which one is on screen ---------------------------------------------

    @Test
    fun `the active id picks the class out of the list`() {
        val prefs = mutablePreferencesOf()
        prefs.writeMemberships(listOf(session(1, "7А"), session(2, "9Б")))
        prefs[MembershipKeys.ACTIVE_CLASS_ID] = 2L

        assertEquals("9Б", prefs.activeMembership()?.className)
    }

    @Test
    fun `an active id naming nobody falls back to the first class`() {
        // The state a half-finished write leaves. The phone is in classes, so
        // it shows one: claiming to be in none would send somebody who is in
        // two classes to the join screen.
        val prefs = mutablePreferencesOf()
        prefs.writeMemberships(listOf(session(1, "7А"), session(2, "9Б")))
        prefs[MembershipKeys.ACTIVE_CLASS_ID] = 99L

        assertEquals("7А", prefs.activeMembership()?.className)
    }

    // -- damage -------------------------------------------------------------

    @Test
    fun `an unreadable list reads as empty rather than throwing`() {
        // It is decoded inside `dataStore.edit`, so a throw here would take the
        // write with it and leave a phone that can neither join nor sign out.
        // The memberships are recoverable with a join code; that state is not.
        val prefs = mutablePreferencesOf(MembershipKeys.SESSIONS to "{not json at all")

        assertEquals(emptyList<Session>(), prefs.memberships())
        assertNull(prefs.activeMembership())
    }

    @Test
    fun `one damaged record does not take the other memberships with it`() {
        // The list is decoded a record at a time for exactly this. Decoded as a
        // list, one bad element reported a phone holding two valid tokens as
        // being in no class — and the first thing a user does to recover is
        // enter a join code, which overwrites the survivors for good.
        val prefs = mutablePreferencesOf(
            MembershipKeys.SESSIONS to
                """[{"classId":1,"className":"7А","token":"t1"},""" +
                """{"classId":"not a number","className":"9Б","token":"t2"},""" +
                """{"classId":3,"className":"11В","token":"t3"}]""",
        )

        assertEquals(listOf(1L, 3L), prefs.memberships().map { it.classId })
    }

    @Test
    fun `a null where a newer build wrote one falls back to the default`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.SESSIONS to
                """[{"classId":1,"className":null,"school":null,"token":"t1"}]""",
        )

        assertEquals(listOf(1L), prefs.memberships().map { it.classId })
        assertEquals("", prefs.memberships().single().className)
    }

    @Test
    fun `a record with no token is dropped and the rest survive`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.SESSIONS to
                """[{"classId":1,"className":"7А","token":""},{"classId":2,"className":"9Б","token":"t2"}]""",
        )

        assertEquals(listOf(2L), prefs.memberships().map { it.classId })
    }

    @Test
    fun `a record written by a newer build keeps its known fields`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.SESSIONS to
                """[{"classId":1,"className":"7А","token":"t1","somethingNew":42}]""",
        )

        assertEquals(listOf(session(1, "7А")), prefs.memberships())
    }

    @Test
    fun `clearing forgets the list and the keys it replaced`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.TOKEN to "old-token",
            MembershipKeys.CLASS_ID to 7L,
        )
        prefs.writeMemberships(listOf(session(1, "7А")))
        prefs[MembershipKeys.ACTIVE_CLASS_ID] = 1L

        prefs.clearMemberships()

        assertEquals(emptyList<Session>(), prefs.memberships())
        assertNull(prefs.activeMembership())
        assertNull(prefs[MembershipKeys.ACTIVE_CLASS_ID])
    }

    // -- the class's diary binding (gap 3) -----------------------------------

    @Test
    fun `a session stored before bindings reads as unbound`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.SESSIONS to """[{"classId":1,"className":"7А","token":"t1"}]""",
        )

        assertNull(prefs.memberships().single().diary)
    }

    @Test
    fun `a binding round-trips with its membership`() {
        val bound = session(1, "7А").copy(
            diary = DiaryBinding(DiaryProviderKey.NETSCHOOL, "samara", 1234, "Школа № 5"),
        )
        val prefs = mutablePreferencesOf()

        prefs.writeMemberships(listOf(bound, session(2, "9Б")))

        assertEquals(listOf(bound, session(2, "9Б")), prefs.memberships())
    }

    /** A binding this build cannot read costs the binding — never the class and its token. */
    @Test
    fun `an unreadable binding costs the binding and not the membership`() {
        val prefs = mutablePreferencesOf(
            MembershipKeys.SESSIONS to
                """[{"classId":1,"className":"7А","token":"t1","diary":{"provider":"eljur","region":"x"}},""" +
                """{"classId":2,"className":"9Б","token":"t2","diary":"garbage"},""" +
                """{"classId":3,"className":"5В","token":"t3","diary":{"provider":"netschool","schoolId":"not a number"}}]""",
        )

        val read = prefs.memberships()

        assertEquals(listOf(1L, 2L, 3L), read.map { it.classId })
        assertEquals(listOf<DiaryBinding?>(null, null, null), read.map { it.diary })
    }
}

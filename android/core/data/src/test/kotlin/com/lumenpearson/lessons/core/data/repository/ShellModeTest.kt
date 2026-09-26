package com.lumenpearson.lessons.core.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.lumenpearson.lessons.core.data.datastore.DiaryKeys
import com.lumenpearson.lessons.core.data.datastore.ShellKeys
import com.lumenpearson.lessons.core.data.datastore.shellState
import com.lumenpearson.lessons.core.data.datastore.writeMemberships
import com.lumenpearson.lessons.core.data.diary.MemoryDiaryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which home the app opens on, and the two decisions that hang off it.
 *
 * The mode is read from the stored keys only, in one emission, so these tests
 * write keys into a plain `MutablePreferences` — the same function the
 * preferences' flow maps every emission through.
 */
class ShellModeTest {

    private val session = Session(classId = 7, className = "7A", school = null, token = "class-token")
    private val samara = DiaryTarget.netschool("samara", 1234, "School 5", "ivanova", "Europe/Samara")

    @Test
    fun `a class outranks a diary`() {
        assertEquals(ShellMode.CLASS, shellModeOf(session, samara))
        assertEquals(ShellMode.CLASS, shellModeOf(session, null))
    }

    @Test
    fun `a diary without a class is the diary home, not the join screen`() {
        assertEquals(ShellMode.DIARY, shellModeOf(null, samara))
    }

    @Test
    fun `nothing stored is none`() {
        assertEquals(ShellMode.NONE, shellModeOf(null, null))
        assertEquals(ShellState(ShellMode.NONE, held = false), mutablePreferencesOf().shellState())
    }

    /** A bare `401` drops the bearer and keeps the target: the family belongs on their diary's form. */
    @Test
    fun `a diary target without a token is still diary mode`() {
        val prefs = mutablePreferencesOf(
            DiaryKeys.TARGET to DiaryTargetCodec.encode(samara),
        )
        assertEquals(ShellMode.DIARY, prefs.shellState().mode)
    }

    @Test
    fun `an install from before targets, signed in to Petersburg, is diary mode`() {
        val prefs = mutablePreferencesOf(
            DiaryKeys.TOKEN to "diary-token",
            DiaryKeys.LOGIN to "parent@example.com",
        )
        assertEquals(ShellMode.DIARY, prefs.shellState().mode)
    }

    /** #151: leaving the last class with a diary signed in lands on the diary, where its sign-out is. */
    @Test
    fun `leaving the last class with a diary signed in is diary mode`() {
        val prefs = mutablePreferencesOf(DiaryKeys.TARGET to DiaryTargetCodec.encode(samara))
        prefs.writeMemberships(listOf(session))
        assertEquals(ShellMode.CLASS, prefs.shellState().mode)

        prefs.writeMemberships(emptyList())

        assertEquals(ShellMode.DIARY, prefs.shellState().mode)
    }

    @Test
    fun `the hold is read with the mode, from the same keys`() {
        val prefs = mutablePreferencesOf(ShellKeys.ONBOARDING_HELD to true)
        prefs.writeMemberships(listOf(session))

        assertEquals(ShellState(ShellMode.CLASS, held = true), prefs.shellState())
    }

    /** #152: a cold start in no class must not arm the periodic class sync again. */
    @Test
    fun `periodic sync is armed only in a class`() {
        assertEquals(SyncArming.Armed(30), syncArmingFor(ShellMode.CLASS, 30))
        assertEquals(SyncArming.Disarmed, syncArmingFor(ShellMode.NONE, 30))
        assertEquals(SyncArming.Disarmed, syncArmingFor(ShellMode.DIARY, 30))
    }

    /** K24: the widget's sentence depends on the mode, and it redraws only when told. */
    @Test
    fun `the mode changes a registration or a sign-out makes are announced, and a dead bearer is not`() = runTest {
        var announced = 0
        val store = ModeAnnouncingDiaryStore(MemoryDiaryStore(session = null)) { announced++ }

        store.writeDiarySession(DiarySession(login = "ivanova", token = "t", target = samara))
        assertEquals(1, announced)

        store.clearDiaryToken()
        assertEquals(1, announced)

        store.forgetDiary()
        assertEquals(2, announced)
    }

    @Test
    fun `the store still does what it is asked when it announces`() = runTest {
        val inner = MemoryDiaryStore(session = null)
        val store = ModeAnnouncingDiaryStore(inner) {}

        store.writeDiarySession(DiarySession(login = "ivanova", token = "t", target = samara))
        assertTrue(inner.session != null)

        store.forgetDiary()
        assertFalse(inner.session != null)
    }
}

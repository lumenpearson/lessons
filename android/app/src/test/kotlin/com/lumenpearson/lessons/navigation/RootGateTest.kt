package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.data.repository.shellModeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gate below the theme: splash, the way in, or a home — and which home.
 *
 * Nothing composes `LessonsApp` in a test, so this rule is what holds the gate.
 * It is kept pure for that reason, and the shell only carries it out.
 */
class RootGateTest {

    private val petersburg = DiaryTarget.petersburg("parent@example.com")
    private val seventhA = Session(classId = 7, className = "7А", school = null, token = "t")

    /**
     * #151. A family signed in to their diary leaves their last class: no class,
     * a diary. The gate used to ask only «is there a class session» and sent
     * the phone to the join screen, where neither the diary nor its sign-out
     * could be reached, while the diary's session went on being kept alive for
     * a month.
     */
    @Test
    fun `a phone with a diary and no class opens on the diary home`() {
        val mode = shellModeOf(classSession = null, diaryTarget = petersburg)
        val shell = ShellState(mode, held = false)

        assertEquals(RootScreen.HOME, rootScreen(shell))
        assertEquals(ShellHome.DIARY, shellHome(shell.mode))
    }

    @Test
    fun `a class outranks a diary`() {
        val mode = shellModeOf(classSession = seventhA, diaryTarget = petersburg)

        assertEquals(RootScreen.HOME, rootScreen(ShellState(mode, held = false)))
        assertEquals(ShellHome.TIMETABLE, shellHome(mode))
    }

    @Test
    fun `nothing stored is the way in`() {
        assertEquals(RootScreen.ONBOARDING, rootScreen(ShellState(ShellMode.NONE, held = false)))
        assertNull(shellHome(ShellMode.NONE))
    }

    @Test
    fun `nothing read yet is the splash`() {
        assertEquals(RootScreen.SPLASH, rootScreen(null))
    }

    /**
     * K3: the class-code and sign-in steps write their credential before the
     * flow has finished, and the hold keeps the flow on screen through the
     * write — whichever home the write has just made.
     */
    @Test
    fun `a hold keeps the way in on screen whatever the mode`() {
        for (mode in ShellMode.entries) {
            assertEquals(mode.name, RootScreen.ONBOARDING, rootScreen(ShellState(mode, held = true)))
        }
    }
}

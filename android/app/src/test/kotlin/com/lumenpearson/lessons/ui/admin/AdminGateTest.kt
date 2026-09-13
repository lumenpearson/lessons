package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ClassRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one sentence that decides who sees the management page.
 *
 * Worth its own test rather than being read off the source because it is asked
 * in three places — the landing list, the page itself, the toolbar's shortcut —
 * and the failure mode of getting it wrong is silent in two directions. Too
 * strict and an administrator loses the page without being told; too loose and
 * every pupil gets a page of buttons the server will refuse, which is a worse
 * kind of lie than a missing row.
 */
class AdminGateTest {

    @Test
    fun `an owner manages the class`() {
        assertTrue(isClassManager(ClassRole.OWNER))
    }

    @Test
    fun `an administrator manages the class`() {
        assertTrue(isClassManager(ClassRole.ADMIN))
    }

    @Test
    fun `an editor may write but may not manage`() {
        assertFalse(isClassManager(ClassRole.EDITOR))
    }

    @Test
    fun `a viewer does not`() {
        assertFalse(isClassManager(ClassRole.VIEWER))
    }

    @Test
    fun `an unanswered role is not an administrator`() {
        // The state before the first `/me` comes back, and the state of a phone
        // tied to no account. Defaulting the other way would flash the page at
        // everybody for as long as the request takes.
        assertFalse(isClassManager(null))
    }
}

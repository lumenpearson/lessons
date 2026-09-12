package com.lumenpearson.lessons.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one button beside the pill, and which of three things it is.
 *
 * There is exactly one action slot in the whole app and three screens compete
 * for it, so the rule is small and load-bearing in equal measure: get it wrong
 * on the documentation and the only way out of a screen with no back arrow
 * disappears.
 */
class ShellActionTest {

    @Test
    fun `the tabs offer the way into settings`() {
        assertEquals(
            ShellActionKind.SETTINGS,
            shellActionKind(ShellDestination.TABS, manager = false),
        )
    }

    /** Being an administrator changes nothing on the tabs: settings comes first. */
    @Test
    fun `an administrator still gets settings from the tabs`() {
        assertEquals(
            ShellActionKind.SETTINGS,
            shellActionKind(ShellDestination.TABS, manager = true),
        )
    }

    @Test
    fun `inside settings an administrator gets the bug button`() {
        assertEquals(
            ShellActionKind.DEBUG,
            shellActionKind(ShellDestination.SETTINGS, manager = true),
        )
    }

    /** A gap, not a button that refuses; see [shellActionKind]. */
    @Test
    fun `inside settings everybody else gets nothing`() {
        assertEquals(
            ShellActionKind.NONE,
            shellActionKind(ShellDestination.SETTINGS, manager = false),
        )
    }

    /**
     * The documentation's pill is its table of contents, so the bar has no back
     * arrow of its own and the action slot has to be it — for an administrator
     * as much as for anybody, which is the case the bug button would have
     * quietly taken.
     */
    @Test
    fun `the documentation always offers back`() {
        assertEquals(
            ShellActionKind.BACK,
            shellActionKind(ShellDestination.DOCS, manager = false),
        )
        assertEquals(
            ShellActionKind.BACK,
            shellActionKind(ShellDestination.DOCS, manager = true),
        )
    }
}

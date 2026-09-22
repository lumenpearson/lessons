package com.lumenpearson.lessons.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One back press, five layers, and only one of them may act.
 *
 * Same shape as [ShellActionTest] and for the same reason: the shell registers
 * a handler per layer, so «which one is enabled» is a rule spread across four
 * `enabled =` expressions that have to be read against each other. Stated once,
 * it can be asked.
 */
class ShellBackTest {

    /**
     * The one the tab bar's arranging mode needs. It can only be armed on the
     * tabs, so the pager below it is live and its predictive handler would
     * otherwise take the press — and on the default tab, where even that is
     * disabled, the press would leave the app with the bar still wobbling.
     */
    @Test
    fun `a back press while the tabs are being arranged only leaves that mode`() {
        assertEquals(
            ShellBack.LEAVE_ARRANGING,
            shellBack(
                arranging = true,
                docsOpen = false,
                sectionOpen = false,
                settingsOpen = false,
                onHomePage = false,
            ),
        )
        assertEquals(
            "on the default tab there is nothing else to claim it, and the press " +
                "would have left the app",
            ShellBack.LEAVE_ARRANGING,
            shellBack(
                arranging = true,
                docsOpen = false,
                sectionOpen = false,
                settingsOpen = false,
                onHomePage = true,
            ),
        )
    }

    /**
     * The mode is closed by everything that opens a page over the tabs, so this
     * combination should not arise. It is pinned anyway: «cannot happen» is the
     * assumption that decides a precedence order, and the next page added to
     * the shell is the one that breaks it.
     */
    @Test
    fun `arranging outranks every page, in case one is ever open under it`() {
        assertEquals(
            ShellBack.LEAVE_ARRANGING,
            shellBack(
                arranging = true,
                docsOpen = true,
                sectionOpen = true,
                settingsOpen = true,
                onHomePage = false,
            ),
        )
    }

    /** The documentation is opened from inside a section, so it has to win. */
    @Test
    fun `the documentation leaves before the settings tree it was opened from`() {
        assertEquals(
            ShellBack.CLOSE_DOCS,
            shellBack(
                arranging = false,
                docsOpen = true,
                sectionOpen = true,
                settingsOpen = true,
                onHomePage = false,
            ),
        )
    }

    @Test
    fun `a section closes before the root it sits on`() {
        assertEquals(
            ShellBack.CLOSE_SECTION,
            shellBack(
                arranging = false,
                docsOpen = false,
                sectionOpen = true,
                settingsOpen = true,
                onHomePage = false,
            ),
        )
    }

    @Test
    fun `the settings root closes back onto the tabs`() {
        assertEquals(
            ShellBack.CLOSE_SETTINGS,
            shellBack(
                arranging = false,
                docsOpen = false,
                sectionOpen = false,
                settingsOpen = true,
                onHomePage = false,
            ),
        )
    }

    /**
     * With nothing open, back is the predictive gesture home — and on the
     * default tab it is not ours at all, which is what lets the app be left.
     */
    @Test
    fun `on the tabs back goes home, and from home it leaves the app`() {
        assertEquals(
            ShellBack.HOME,
            shellBack(
                arranging = false,
                docsOpen = false,
                sectionOpen = false,
                settingsOpen = false,
                onHomePage = false,
            ),
        )
        assertEquals(
            ShellBack.SYSTEM,
            shellBack(
                arranging = false,
                docsOpen = false,
                sectionOpen = false,
                settingsOpen = false,
                onHomePage = true,
            ),
        )
    }
}

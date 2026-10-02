package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.ui.settings.SettingsSection
import com.lumenpearson.lessons.ui.settings.SettingsSection.ABOUT
import com.lumenpearson.lessons.ui.settings.SettingsSection.ALERTS
import com.lumenpearson.lessons.ui.settings.SettingsSection.PERMISSIONS
import com.lumenpearson.lessons.ui.settings.SettingsSection.SYNC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back from a settings page lands on the page it was opened from (#243), and the
 * back pill names that page (#244).
 *
 * The shell used to keep one open section, so «Разрешения» opened from
 * «Уведомления» overwrote it and back landed on the root. These ask the path
 * the shell now keeps; that the shell's back handlers and its pill read it is
 * seen on a device, not composed here — nothing composes the shell in a test.
 */
class SettingsTrailTest {

    private val root = SettingsTrail()

    @Test
    fun `a page opened from the root is the whole path`() {
        assertEquals(listOf(ALERTS), root.opened(ALERTS, from = null).sections)
    }

    @Test
    fun `back from a page opened from a page lands on that page, not on the root`() {
        val permissions = root.opened(ALERTS, from = null).opened(PERMISSIONS, from = ALERTS)

        assertEquals(listOf(ALERTS, PERMISSIONS), permissions.sections)
        assertEquals(ALERTS, permissions.closed().top)
        assertNull("one more back is the root", permissions.closed().closed().top)
    }

    /**
     * A page sliding away is still composed, and its rows still take taps. One
     * tapped there opens from the page it was on, not on top of the one that
     * has just come in.
     */
    @Test
    fun `a page opened from one under the top replaces what was above it`() {
        val deep = root.opened(ALERTS, from = null).opened(PERMISSIONS, from = ALERTS)

        assertEquals(listOf(ALERTS, SYNC), deep.opened(SYNC, from = ALERTS).sections)
    }

    @Test
    fun `a page opened from one no longer on the path opens from the root`() {
        val sync = root.opened(SYNC, from = null)

        assertEquals(listOf(ABOUT), sync.opened(ABOUT, from = ALERTS).sections)
    }

    @Test
    fun `the page in front knows the page under it and how deep it is`() {
        val first = root.opened(ALERTS, from = null).page()
        val second = root.opened(ALERTS, from = null).opened(PERMISSIONS, from = ALERTS).page()

        assertEquals(ShellPage.Section(ALERTS, parent = null, level = 0), first)
        assertEquals(ShellPage.Section(PERMISSIONS, parent = ALERTS, level = 1), second)
        assertNull(root.page())
    }

    /** Depth is what the slide's direction is read from. */
    @Test
    fun `a page opened from a page slides forward, and the guide is deeper still`() {
        val first = root.opened(ABOUT, from = null).page()!!
        val second = root.opened(ALERTS, from = null).opened(PERMISSIONS, from = ALERTS).page()!!

        assertTrue(ShellPage.SettingsRoot.depth < first.depth)
        assertTrue(first.depth < second.depth)
        assertTrue(second.depth < ShellPage.Docs.depth)
    }

    @Test
    fun `the path survives being saved, in order`() {
        val path = root.opened(ALERTS, from = null).opened(PERMISSIONS, from = ALERTS)

        assertEquals(path, SettingsTrail.decode(path.encode()))
        assertEquals(root, SettingsTrail.decode(""))
        assertEquals(root, SettingsTrail.decode(null))
    }

    /** A section an update removed: the deepest page that still exists. */
    @Test
    fun `a saved name this build does not have ends the path there`() {
        assertEquals(listOf(ALERTS), SettingsTrail.decode("ALERTS,GONE,PERMISSIONS").sections)
        assertEquals(root, SettingsTrail.decode("GONE"))
    }

    @Test
    fun `on the settings root the pill names the tab it was opened from`() {
        assertEquals(R.string.nav_today, backLabel(ShellPage.SettingsRoot, tabLabel = R.string.nav_today))
    }

    @Test
    fun `on a page opened from the root the pill names the root`() {
        assertEquals(R.string.settings_title, backLabel(ShellPage.Section(SYNC), tabLabel = R.string.nav_today))
    }

    @Test
    fun `on a page opened from a page the pill names that page`() {
        val permissions = ShellPage.Section(PERMISSIONS, parent = ALERTS, level = 1)

        assertEquals(ALERTS.titleRes, backLabel(permissions, tabLabel = R.string.nav_today))
    }

    @Test
    fun `the tabs and the guide have no back pill to name anything`() {
        assertNull(backLabel(ShellPage.Tabs, tabLabel = R.string.nav_today))
        assertNull(backLabel(ShellPage.Docs, tabLabel = R.string.nav_today))
    }

    @Test
    fun `every section's title is a page name the pill can show`() {
        SettingsSection.entries.forEach { section ->
            val above = ShellPage.Section(PERMISSIONS, parent = section, level = 1)
            assertEquals(section.titleRes, backLabel(above, tabLabel = R.string.nav_today))
        }
    }
}

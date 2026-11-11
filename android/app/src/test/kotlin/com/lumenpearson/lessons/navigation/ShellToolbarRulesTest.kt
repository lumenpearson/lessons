package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.designsystem.theme.ScrollOffsetHolder
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ShellToolbarRulesTest {
    @Test
    fun `the kept tab wins while a reordered pager settles`() {
        val displayed = listOf(HomeTab.WEEK, HomeTab.TODAY)
        assertEquals(1, timetableToolbarIndex(HomeTab.TODAY, displayed) { error("pager read during reorder") })
        assertEquals(0, timetableToolbarIndex(null, displayed) { HomeTab.WEEK })
        assertEquals(-1, timetableToolbarIndex(null, displayed) { HomeTab.HOMEWORK })
        assertEquals(-1, timetableToolbarIndex(null, displayed) { null })
    }

    @Test
    fun `only the active destination supplies toolbar content and scroll state`() {
        val section = ShellPage.Section(SettingsSection.APPEARANCE)
        val tabs = ScrollOffsetHolder()
        val settings = ScrollOffsetHolder()
        val appearance = ScrollOffsetHolder()
        val docs = ScrollOffsetHolder()
        for ((page, expected) in listOf(
            ShellPage.Tabs to tabs, ShellPage.SettingsRoot to settings, section to appearance, ShellPage.Docs to docs,
        )) {
            val chosen = mutableListOf<String>()
            page.toolbarContent({ chosen.add("tabs") }, { chosen.add("docs") }, { chosen.add("settings") })
            val content = when (page) {
                ShellPage.Tabs -> "tabs"
                ShellPage.Docs -> "docs"
                else -> "settings"
            }
            assertEquals(listOf(content), chosen)
            assertSame(expected, page.shellOffset({ tabs }, settings, { key ->
                assertEquals(section.section, key)
                appearance
            }, { docs }))
        }
    }

    @Test
    fun `back closes the level currently drawn by the pill`() {
        val closeRoot = {}
        val closeSection = {}
        assertNull(ShellPage.Tabs.toolbarBack(closeRoot, closeSection))
        assertNull(ShellPage.Docs.toolbarBack(closeRoot, closeSection))
        assertSame(closeRoot, ShellPage.SettingsRoot.toolbarBack(closeRoot, closeSection))
        assertSame(closeSection, ShellPage.Section(SettingsSection.APPEARANCE).toolbarBack(closeRoot, closeSection))
    }
}

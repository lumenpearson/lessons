package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Test

/** The names the developer mode's activity record gives the shell's pages (#237). */
class ScreenNameTest {

    @Test
    fun `every page has a name, and a tab names itself`() {
        assertEquals("tab WEEK", screenName(ShellPage.Tabs, HomeTab.WEEK))
        assertEquals("tab none", screenName(ShellPage.Tabs, null))
        assertEquals("settings", screenName(ShellPage.SettingsRoot, HomeTab.TODAY))
        assertEquals("settings/DEVELOPER", screenName(ShellPage.Section(SettingsSection.DEVELOPER), null))
        assertEquals("guide", screenName(ShellPage.Docs, null))
    }
}

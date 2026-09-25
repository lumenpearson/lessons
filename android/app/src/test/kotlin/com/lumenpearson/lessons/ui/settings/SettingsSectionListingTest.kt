package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.core.data.repository.ShellMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows the settings root offers, per home — `SettingsSection.listedOn`,
 * the rule that replaced a flag which could only say «never on the root».
 */
class SettingsSectionListingTest {

    private fun listed(mode: ShellMode, manager: Boolean) =
        SettingsSection.entries.filter { it.listedOn(mode, manager) }

    /** Pinned: a phone in a class sees exactly what it saw before the diary home existed. */
    @Test
    fun `class mode lists what it lists today`() {
        val everyone = listOf(
            SettingsSection.APPEARANCE,
            SettingsSection.FEEL,
            SettingsSection.CONTENT,
            SettingsSection.ALERTS,
            SettingsSection.SYNC,
            SettingsSection.ACCOUNT,
            SettingsSection.DIARY,
            SettingsSection.UPDATES,
            SettingsSection.ABOUT,
        )
        assertEquals(everyone, listed(ShellMode.CLASS, manager = false))
        assertEquals(
            everyone + SettingsSection.ADMIN,
            listed(ShellMode.CLASS, manager = true),
        )
    }

    /**
     * #151's other half: on the diary home, «Дневник» is on the list — it is
     * where the account and «Выйти из дневника» are — and nothing is that
     * needs a class token or a class timetable, each of which would be a page
     * of switches that do nothing or a request that answers 401.
     */
    @Test
    fun `diary mode lists the diary and no page that needs a class`() {
        for (manager in listOf(false, true)) {
            val sections = listed(ShellMode.DIARY, manager)
            assertTrue(SettingsSection.DIARY in sections)
            for (classOnly in listOf(
                SettingsSection.CONTENT,
                SettingsSection.ALERTS,
                SettingsSection.ACCOUNT,
                SettingsSection.ADMIN,
                SettingsSection.PERMISSIONS,
            )) {
                assertTrue("$classOnly listed on the diary home", classOnly !in sections)
            }
        }
        assertEquals(
            listOf(
                SettingsSection.APPEARANCE,
                SettingsSection.FEEL,
                SettingsSection.SYNC,
                SettingsSection.DIARY,
                SettingsSection.UPDATES,
                SettingsSection.ABOUT,
            ),
            listed(ShellMode.DIARY, manager = false),
        )
    }

    @Test
    fun `permissions is reached from the alerts page, never from the root`() {
        for (mode in ShellMode.entries) {
            assertTrue(SettingsSection.PERMISSIONS !in listed(mode, manager = true))
        }
    }
}

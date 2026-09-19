package com.lumenpearson.lessons.ui

import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one preference that must not be acted on before it has been read.
 *
 * Most of the shell may draw the placeholder `AppShellUiState()` for a frame:
 * the default theme arrives, the stored one replaces it, and nobody is any the
 * worse. The language is the exception, because below API 33 the only way to
 * change the language of a drawn screen is `Activity.recreate()` — so acting on
 * a placeholder does not paint a wrong frame, it throws the activity away.
 *
 * What that costs is not the frame. The recreation re-enters `onCreate` with an
 * Intent that `consumeRequestedDate` has already emptied and a fresh
 * `pendingDate`, so a tap on a day in the home-screen widget is read out of the
 * Intent and then discarded with the activity that held it: the app opens on
 * the default tab, having been asked for a date, with nothing logged.
 *
 * The trap is that the placeholder is not obviously one. `AppSettings()` says
 * [AppLanguage.SYSTEM] because that is a `data class` default, and `SYSTEM` is
 * also a real answer somebody can choose — so «not read yet» and «the phone's
 * language, please» were the same value, and `MainActivity` compared them
 * against the language it really was attached in.
 */
class AppShellLanguageTest {

    @Test
    fun `the placeholder state asks for no language at all`() {
        assertNull(AppShellUiState().languageToApply)
    }

    /**
     * The case that made it a defect rather than a frame: the placeholder says
     * SYSTEM, the activity is attached in RU, and the two compare unequal.
     */
    @Test
    fun `a placeholder that happens to say SYSTEM is still not an answer`() {
        val placeholder = AppShellUiState(
            settings = AppSettings(language = AppLanguage.SYSTEM),
            settingsLoaded = false,
        )
        assertNull(placeholder.languageToApply)
    }

    @Test
    fun `a loaded state hands over what is stored`() {
        for (language in AppLanguage.entries) {
            val loaded = AppShellUiState(
                settings = AppSettings(language = language),
                settingsLoaded = true,
            )
            assertEquals(language, loaded.languageToApply)
        }
    }

    /**
     * `signedIn` is a different question and must not be mistaken for this one.
     * It happens to be `null` for as long as the settings are unread, but only
     * because two independent reads are racing in the same view model, and a
     * gate built on that is a gate held up by an accident.
     */
    @Test
    fun `being signed in decides nothing about the language`() {
        val notLoaded = AppShellUiState(signedIn = true, settingsLoaded = false)
        assertNull(notLoaded.languageToApply)

        val loaded = AppShellUiState(
            settings = AppSettings(language = AppLanguage.ENGLISH),
            signedIn = null,
            settingsLoaded = true,
        )
        assertEquals(AppLanguage.ENGLISH, loaded.languageToApply)
    }
}

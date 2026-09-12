package com.lumenpearson.lessons.core.data.locale

import com.lumenpearson.lessons.core.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The language rule, tested where it is a function of two lists and nothing
 * else.
 *
 * Nothing here touches Android on purpose. What could actually go wrong in this
 * feature is a decision — "follow the phone" quietly becoming "force Russian",
 * or the phone's own locale being left behind the chosen one as a fallback that
 * leaks a Russian string into an English notification — and both are decisions
 * [localeOverrideFor] makes before any framework call happens. Wrapping a
 * `Context` is then three lines of `createConfigurationContext`, which a JVM
 * test can only verify by mocking the framework into agreeing with it.
 */
class LocaleOverrideTest {

    @Test
    fun `system never overrides, whatever the phone is set to`() {
        // The bug this exists to prevent: AppLanguage.SYSTEM carries an empty
        // tag, and `values/` is Russian, so a rule that reached for the tag
        // without thinking would hand every English phone that never touched the
        // setting a Russian app.
        assertNull(localeOverrideFor(AppLanguage.SYSTEM, listOf("en-US")))
        assertNull(localeOverrideFor(AppLanguage.SYSTEM, listOf("ru-RU")))
        assertNull(localeOverrideFor(AppLanguage.SYSTEM, listOf("de-DE", "en-GB")))
        assertNull(localeOverrideFor(AppLanguage.SYSTEM, emptyList()))
    }

    @Test
    fun `a chosen language overrides a phone set to another one`() {
        assertEquals(listOf("en"), localeOverrideFor(AppLanguage.ENGLISH, listOf("ru-RU")))
        assertEquals(listOf("ru"), localeOverrideFor(AppLanguage.RUSSIAN, listOf("en-US")))
        // A language the app does not ship is no different from any other
        // language it is not: the choice wins.
        assertEquals(listOf("en"), localeOverrideFor(AppLanguage.ENGLISH, listOf("de-DE")))
    }

    @Test
    fun `the override is one locale, never the phone's list with the choice in front`() {
        // The list is what resource resolution walks. A second entry behind the
        // chosen language would let a string missing from `values-en/` come back
        // in the phone's language rather than in the app's own default.
        assertEquals(
            listOf("en"),
            localeOverrideFor(AppLanguage.ENGLISH, listOf("ru-RU", "en-GB", "de-DE")),
        )
    }

    @Test
    fun `a phone already in that one language is left alone`() {
        // Not tidiness: this runs on every widget redraw and every alert
        // broadcast, and it is the difference between allocating a Configuration
        // and a Context per render and doing nothing at all.
        assertNull(localeOverrideFor(AppLanguage.RUSSIAN, listOf("ru-RU")))
        assertNull(localeOverrideFor(AppLanguage.ENGLISH, listOf("en-US")))
    }

    @Test
    fun `a region is not a fourth language`() {
        // `en-GB` is English, so English is already what that phone resolves.
        assertNull(localeOverrideFor(AppLanguage.ENGLISH, listOf("en-GB")))
        assertNull(localeOverrideFor(AppLanguage.RUSSIAN, listOf("ru-BY")))
    }

    @Test
    fun `a matching first locale with others behind it is still overridden`() {
        // The phone leads with English and the user chose English, but `ru-RU`
        // sits behind it as a fallback — which is exactly the arrangement the
        // single-entry rule exists for, so this one is not a no-op.
        assertEquals(
            listOf("en"),
            localeOverrideFor(AppLanguage.ENGLISH, listOf("en-US", "ru-RU")),
        )
    }

    @Test
    fun `no override leaves the process in the phone's language`() {
        assertEquals(RussianPhone, processLocaleFor(null, RussianPhone))
    }

    @Test
    fun `an override moves the process default with it`() {
        assertEquals(
            Locale.forLanguageTag("en"),
            processLocaleFor(listOf("en"), RussianPhone),
        )
    }

    @Test
    fun `the process follows the first locale, the one resources resolve through`() {
        assertEquals(
            Locale.forLanguageTag("en"),
            processLocaleFor(listOf("en", "ru"), RussianPhone),
        )
    }

    private companion object {
        /** Stands in for whatever the process defaulted to before we touched it. */
        val RussianPhone: Locale = Locale.forLanguageTag("ru-RU")
    }
}

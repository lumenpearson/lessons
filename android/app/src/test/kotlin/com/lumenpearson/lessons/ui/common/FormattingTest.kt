package com.lumenpearson.lessons.ui.common

import java.time.LocalDate
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * That the app's dates follow the app's language, and keep following it.
 *
 * `AppLocale` sets the process default alongside every context it wraps, so
 * [Locale.getDefault] is the app's chosen language and not the phone's — and
 * changing the language while the app is running changes it again, without the
 * process dying. Everything in `Formatting` therefore has to read the locale
 * when it formats rather than when it was loaded: a [java.time.format
 * .DateTimeFormatter] built as a file-level `val` binds the locale that was in
 * force at class initialisation and prints September in Russian for the rest of
 * the process.
 *
 * The two functions below are the ones that carry a word rather than digits,
 * which is the whole of where this can be seen.
 */
class FormattingTest {

    private val locale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
    }

    /** The genitive «8 сентября», which is what java.time is used for here. */
    @Test
    fun `a day and month are declined for the current language`() {
        assertEquals("8 сентября", LocalDate.of(2026, 9, 8).asDayMonth())
    }

    /**
     * The language changed under a process that is still running.
     *
     * Below Android 13 this is `recreate()` after `Locale.setDefault`; from 13
     * on the platform does the same thing itself. Neither kills the process, so
     * anything that captured the locale once is still holding the old one.
     */
    @Test
    fun `a day and month follow a language change`() {
        val date = LocalDate.of(2026, 9, 8)
        assertEquals("8 сентября", date.asDayMonth())

        Locale.setDefault(Locale.ENGLISH)
        assertEquals("8 September", date.asDayMonth())
    }

    /** The same for the month view's own title, which is nominative. */
    @Test
    fun `a month and year follow a language change`() {
        val date = LocalDate.of(2026, 9, 8)
        assertEquals("Сентябрь 2026", date.asMonthYear())

        Locale.setDefault(Locale.ENGLISH)
        assertEquals("September 2026", date.asMonthYear())
    }

    /** Weekday names, in both the places the app asks for them. */
    @Test
    fun `weekdays follow a language change`() {
        val monday = LocalDate.of(2026, 9, 7)
        assertEquals("понедельник", monday.asFullWeekday())

        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Monday", monday.asFullWeekday())
        assertEquals("mon", monday.asShortWeekday())
    }

    /** Times are digits and a colon, and must not move with the language at all. */
    @Test
    fun `a clock is the same in either language`() {
        val date = LocalDate.of(2026, 9, 8)
        assertEquals("08.09", date.asShortDate())

        Locale.setDefault(Locale.ENGLISH)
        assertEquals("08.09", date.asShortDate())
    }
}

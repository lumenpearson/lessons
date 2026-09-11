package com.lumenpearson.lessons.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The personalization enums are stored by name, so `fromName` is the boundary
 * between a preferences file written by some other version of the app and the
 * running one. Everything here is about that boundary holding.
 */
class PersonalizationTest {

    @Test
    fun `every name round-trips`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromName(it.name)) }
        HapticStrength.entries.forEach { assertEquals(it, HapticStrength.fromName(it.name)) }
        HomeTab.entries.forEach { assertEquals(it, HomeTab.fromName(it.name)) }
    }

    @Test
    fun `a missing value falls back to the default`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
        assertEquals(HapticStrength.SUBTLE, HapticStrength.fromName(null))
        assertEquals(HomeTab.TODAY, HomeTab.fromName(null))
    }

    /**
     * A value written by a newer build — or a hand-edited file — must not throw.
     * The old build cannot honour it, but it has to keep starting.
     */
    @Test
    fun `an unknown value falls back to the default`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("AMOLED"))
        assertEquals(HapticStrength.SUBTLE, HapticStrength.fromName("TICK"))
        assertEquals(HomeTab.TODAY, HomeTab.fromName("MARKS"))
    }

    /** Names are matched exactly; a lower-case name is not the same value. */
    @Test
    fun `matching is case sensitive`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("dark"))
        assertEquals(HomeTab.TODAY, HomeTab.fromName("week"))
    }

    /**
     * The bar draws [HomeTab.entries] in order, and the default tab picker
     * offers the same list. Pinning the order here is what stops a reordering
     * from silently moving somebody's stored default to a different screen.
     */
    @Test
    fun `tabs are in bar order`() {
        assertEquals(
            listOf(HomeTab.TODAY, HomeTab.WEEK, HomeTab.HOMEWORK, HomeTab.SETTINGS),
            HomeTab.entries,
        )
    }
}

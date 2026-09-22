package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.model.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The homework preview is the one setting stored as a number rather than as a
 * name, so it is the one that can arrive off the picker's steps — from a
 * hand-edited file, from a build that offered a fourth length, or from a future
 * one that offers fewer. Snapping is what keeps that readable instead of
 * leaving a picker with no segment lit.
 */
class AppSettingsTest {

    @Test
    fun `an offered length is left alone`() {
        AppSettings.HOMEWORK_PREVIEW_OPTIONS.forEach {
            assertEquals(it, AppSettings.nearestHomeworkPreview(it))
        }
    }

    @Test
    fun `a length between two steps lands on the nearer one`() {
        assertEquals(1, AppSettings.nearestHomeworkPreview(2))
        assertEquals(3, AppSettings.nearestHomeworkPreview(4))
    }

    /** Nonsense from a corrupt or hand-edited file still has to render. */
    @Test
    fun `a length outside the offered ones lands on an end`() {
        assertEquals(1, AppSettings.nearestHomeworkPreview(0))
        assertEquals(1, AppSettings.nearestHomeworkPreview(-20))
        assertEquals(5, AppSettings.nearestHomeworkPreview(40))
    }

    /**
     * The shipped default is one of the steps, and it is the number the home
     * screen previewed before the count was a choice.
     */
    @Test
    fun `the default is an offered length`() {
        assertEquals(AppSettings.DEFAULT_HOMEWORK_PREVIEW, AppSettings().todayHomeworkPreview)
        assertTrue(
            AppSettings.DEFAULT_HOMEWORK_PREVIEW in AppSettings.HOMEWORK_PREVIEW_OPTIONS,
        )
    }

    /**
     * The bar somebody who has never opened the setting sees, which is also what
     * every screen gets while the preferences file is still being read. Pinned
     * here because a default that quietly dropped a tab would look like a
     * rendering bug rather than like a default.
     *
     * The mapping that writes this field into the preferences file and reads it
     * back is `LessonsPreferences.toSettings`, which is private to a class that
     * needs a `Context`; this module has no Robolectric, so there is no seam to
     * pin the round trip through. What can be checked without one is checked in
     * `:core:model`, where `HomeTab.order` is the whole of the repair.
     */
    @Test
    fun `the default tab order is every tab, as declared`() {
        assertEquals(HomeTab.entries, AppSettings().tabOrder)
    }
}

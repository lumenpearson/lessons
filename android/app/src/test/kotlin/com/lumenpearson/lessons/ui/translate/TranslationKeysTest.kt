package com.lumenpearson.lessons.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning what the platform knows a resource by into what a translator does.
 *
 * The lookup itself needs a device; the parsing of its answer does not, and the
 * answers worth being sure about are the ones that must be refused — a resource
 * that is not a string cannot be written back as a `<string>` element, and a
 * fragment containing one would not compile for whoever applied it.
 */
class TranslationKeysTest {

    @Test
    fun `a qualified name is reduced to its entry`() {
        assertEquals(
            "settings_title",
            TranslationKeys.entryNameOf("com.lumenpearson.lessons:string/settings_title"),
        )
    }

    /** The debug build's package is suffixed; the entry name is not. */
    @Test
    fun `the package the name came from does not matter`() {
        assertEquals(
            "week_title",
            TranslationKeys.entryNameOf("com.lumenpearson.lessons.debug:string/week_title"),
        )
        assertEquals("ok", TranslationKeys.entryNameOf("android:string/ok"))
    }

    @Test
    fun `anything that is not a string is refused`() {
        assertNull(TranslationKeys.entryNameOf("com.lumenpearson.lessons:plurals/lesson_count"))
        assertNull(TranslationKeys.entryNameOf("com.lumenpearson.lessons:array/themes"))
        assertNull(TranslationKeys.entryNameOf("com.lumenpearson.lessons:drawable/ic_launcher"))
    }

    @Test
    fun `a name the platform could not give is refused`() {
        assertNull(TranslationKeys.entryNameOf(null))
        assertNull(TranslationKeys.entryNameOf(""))
        assertNull(TranslationKeys.entryNameOf("settings_title"))
        assertNull(TranslationKeys.entryNameOf("/settings_title"))
        assertNull(TranslationKeys.entryNameOf("com.lumenpearson.lessons:string/"))
    }
}

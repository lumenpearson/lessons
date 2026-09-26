package com.lumenpearson.lessons.widget.format

import android.content.ContextWrapper
import android.content.res.Resources
import com.lumenpearson.lessons.widget.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nothing set is a sentence on every size of the widget, never «0 предметов» (#173).
 *
 * The one-line sizes printed the plural as it came, so a home screen with two
 * widgets on it said «ДЗ на послезавтра · 0 предметов» in one and «Ничего не
 * задано» in the other. The rule lives in [WidgetStrings.subjectCount] now, and
 * this holds it there.
 *
 * No Robolectric in this module, so the context is a stub whose only real part
 * is `getResources()`: both words are read through it.
 */
class SubjectCountTest {

    @Suppress("DEPRECATION") // the only public constructor; the stub ignores its arguments
    private val resources = object : Resources(null, null, null) {
        override fun getString(id: Int): String {
            check(id == R.string.widget_homework_empty) { "asked for string $id" }
            return NOTHING
        }

        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
            check(id == R.plurals.widget_subject_count) { "asked for plural $id" }
            return "$quantity предмета"
        }
    }

    private val context = object : ContextWrapper(null) {
        override fun getResources(): Resources = this@SubjectCountTest.resources
    }

    @Test
    fun `nothing set reads as a sentence rather than a zero`() {
        assertEquals(NOTHING, WidgetStrings.subjectCount(context, 0))
    }

    @Test
    fun `anything set is still counted`() {
        assertEquals("3 предмета", WidgetStrings.subjectCount(context, 3))
    }

    private companion object {
        const val NOTHING = "Ничего не задано"
    }
}

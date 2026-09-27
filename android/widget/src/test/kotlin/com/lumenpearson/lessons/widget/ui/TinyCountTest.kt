package com.lumenpearson.lessons.widget.ui

import android.content.ContextWrapper
import android.content.res.Resources
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.format.WidgetStrings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * At an enlarged font the one-line size drops the count's noun, not the day (#174).
 *
 * «ДЗ на завтра» carries `defaultWeight()` and the count beside it its own
 * width, so at the largest font the widget read «Д… 3 предмета»: the answer,
 * with the question it answers cut to one letter.
 */
class TinyCountTest {

    @Suppress("DEPRECATION") // the only public constructor; the stub ignores its arguments
    private val resources = object : Resources(null, null, null) {
        override fun getString(id: Int): String {
            check(id == R.string.widget_homework_none_short) { "asked for string $id" }
            return "нет"
        }
    }

    private val context = object : ContextWrapper(null) {
        override fun getResources(): Resources = this@TinyCountTest.resources
    }

    private fun presentation(worded: String, figure: String) = HomeworkPresentation(
        header = "Домашнее задание на завтра",
        shortHeader = "ДЗ на завтра",
        items = emptyList(),
        subjectCount = worded,
        subjectFigure = figure,
        isKnown = true,
    )

    @Test
    fun `at the default font the count keeps its noun`() {
        assertEquals("3 предмета", tinyCountOf(presentation("3 предмета", "3"), fontScale = 1f))
    }

    @Test
    fun `from the first step above the default the figure stands alone`() {
        val three = presentation("3 предмета", "3")
        listOf(1.15f, 1.3f, 1.5f, 1.8f, 2f).forEach { scale ->
            assertEquals("at $scale", "3", tinyCountOf(three, fontScale = scale))
        }
    }

    @Test
    fun `a smaller font keeps the worded count`() {
        assertEquals("3 предмета", tinyCountOf(presentation("3 предмета", "3"), fontScale = 0.85f))
    }

    @Test
    fun `the figure is the number, and nothing set is a word`() {
        assertEquals("3", WidgetStrings.subjectFigure(context, 3))
        assertEquals("нет", WidgetStrings.subjectFigure(context, 0))
    }
}

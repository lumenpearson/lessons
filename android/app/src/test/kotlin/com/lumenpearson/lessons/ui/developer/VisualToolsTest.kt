package com.lumenpearson.lessons.ui.developer

import com.lumenpearson.lessons.core.data.developer.DeveloperTool
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer mode's visual tools (#237): the stretched strings keep a
 * pattern formattable and show both ends, and the switches follow the mode.
 */
class VisualToolsTest {

    @After
    fun tearDown() = VisualTools.follow(emptySet())

    @Test
    fun `a stretched string is bracketed and two fifths longer`() {
        val text = "Расписание"
        val shown = stretched(text)

        assertTrue(shown.startsWith("⟦$text"))
        assertTrue(shown.endsWith("⟧"))
        assertTrue(shown.length >= text.length * 7 / 5 + 2)
    }

    @Test
    fun `a pattern still formats once stretched, every placeholder where it was`() {
        val pattern = "Осталось %1\$d мин до %2\$s"

        val formatted = String.format(Locale.ROOT, stretched(pattern), 7, "Алгебры")

        assertTrue(formatted, formatted.startsWith("⟦Осталось 7 мин до Алгебры"))
        assertTrue(formatted.endsWith("⟧"))
    }

    @Test
    fun `an empty string stays empty, so nothing that was blank draws brackets`() {
        assertEquals("", stretched(""))
    }

    @Test
    fun `the switches follow exactly the tools in force`() {
        VisualTools.follow(setOf(DeveloperTool.LAYOUT_GRID, DeveloperTool.NETWORK_LOG))

        assertTrue(VisualTools.layoutGrid)
        assertFalse(VisualTools.stretchedStrings)
        assertFalse(VisualTools.largeText)

        VisualTools.follow(emptySet())
        assertFalse(VisualTools.layoutGrid)
    }
}

package com.lumenpearson.lessons.widget.ui

import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.WidgetOptions
import com.lumenpearson.lessons.widget.WidgetSizeClass
import com.lumenpearson.lessons.widget.snapshotOf
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * What the widget says in place of a timetable, per way in — and that the mode
 * it says it for is the one the snapshot carries.
 *
 * Two rungs are asked by name, `NARROW` (the 110 dp column) and `LARGE` (a
 * wide one), and then every rung is asked by the width rule, so a rung that
 * lands on the wrong length of sentence is noticed without this list having to
 * grow with the ladder.
 *
 * The texts are read out of the source tree, as `NarrowLabelBudgetTest` reads
 * them: this module's unit tests return defaults from `getString`, so a
 * `Context` would answer nothing.
 */
class EmptyTextTest {

    private val narrow = WidgetSizeClass.NARROW
    private val large = WidgetSizeClass.LARGE

    private val names: Map<Int, String> = mapOf(
        R.string.widget_empty_title to "widget_empty_title",
        R.string.widget_empty_short to "widget_empty_short",
        R.string.widget_no_data_title to "widget_no_data_title",
        R.string.widget_no_data_short to "widget_no_data_short",
        R.string.widget_diary_only_title to "widget_diary_only_title",
        R.string.widget_diary_only_short to "widget_diary_only_short",
    )

    private fun strings(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun drawn(mode: ShellMode, size: WidgetSizeClass, folder: String): String {
        val name = names.getValue(emptyTextRes(mode, size))
        return strings(folder)[name] ?: error("$folder has no $name")
    }

    // -- which sentence --------------------------------------------------------

    @Test
    fun `nobody in yet is told to open the app, at both widths`() {
        assertEquals(R.string.widget_empty_short, emptyTextRes(ShellMode.NONE, narrow))
        assertEquals(R.string.widget_empty_title, emptyTextRes(ShellMode.NONE, large))
    }

    @Test
    fun `a class with nothing synced is told to pull down, at both widths`() {
        assertEquals(R.string.widget_no_data_short, emptyTextRes(ShellMode.CLASS, narrow))
        assertEquals(R.string.widget_no_data_title, emptyTextRes(ShellMode.CLASS, large))
    }

    @Test
    fun `a diary-only phone is told where its diary is, at both widths`() {
        assertEquals(R.string.widget_diary_only_short, emptyTextRes(ShellMode.DIARY, narrow))
        assertEquals(R.string.widget_diary_only_title, emptyTextRes(ShellMode.DIARY, large))
    }

    @Test
    fun `the three ways in never share a sentence on any rung`() {
        for (size in WidgetSizeClass.entries) {
            val texts = ShellMode.entries.map { emptyTextRes(it, size) }
            assertEquals("$size: ${texts.map(names::getValue)}", texts.size, texts.toSet().size)
        }
    }

    @Test
    fun `every narrow rung, and the one-row rung, is given the short sentence`() {
        val shorts = setOf(
            R.string.widget_empty_short,
            R.string.widget_no_data_short,
            R.string.widget_diary_only_short,
        )
        for (size in WidgetSizeClass.entries) {
            // Glance cannot ellipsize, so a long sentence in a 110 dp column is
            // clipped mid-word rather than shortened.
            val compact = size.isNarrow || size == WidgetSizeClass.WIDE
            assertEquals("$size", compact, emptyTextIsCompact(size))
            for (mode in ShellMode.entries) {
                assertEquals("$mode on $size", compact, emptyTextRes(mode, size) in shorts)
            }
        }
        // Both halves of the ladder are still on it; if either vanished, the
        // loop above would pass by asking nothing of it.
        assertTrue(emptyTextIsCompact(narrow))
        assertFalse(emptyTextIsCompact(large))
    }

    // -- what the sentence says ------------------------------------------------

    @Test
    fun `nobody in yet is no longer told a class code is the only way in`() {
        // Two ways in now, and the second is for the family whose class has no
        // code at all; a sentence naming only the first sends them nowhere.
        for (size in listOf(narrow, large)) {
            val russian = drawn(ShellMode.NONE, size, "values")
            assertFalse("«$russian» on $size", russian.contains("введите код класса", ignoreCase = true))
            val english = drawn(ShellMode.NONE, size, "values-en")
            assertFalse("«$english» on $size", english.contains("enter your class code", ignoreCase = true))
        }
    }

    @Test
    fun `a diary-only phone is not promised a timetable a pull would bring`() {
        for (size in listOf(narrow, large)) {
            for (folder in listOf("values", "values-en")) {
                val text = drawn(ShellMode.DIARY, size, folder)
                assertNotEquals("$folder on $size", drawn(ShellMode.CLASS, size, folder), text)
                assertFalse("«$text» in $folder on $size", text.contains("потяните"))
                assertFalse("«$text» in $folder on $size", text.contains("pull down"))
            }
        }
    }

    @Test
    fun `every empty sentence is written in both languages`() {
        for (folder in listOf("values", "values-en")) {
            val table = strings(folder)
            for (name in names.values) {
                assertTrue("$folder has no $name", table[name]?.isNotBlank() == true)
            }
        }
    }

    // -- the snapshot carries the mode, and only a class draws a timetable -----

    /** A Monday at 08:40, ten minutes into the first lesson. */
    private val now = LocalDateTime.of(2026, 9, 21, 8, 40)

    private val timetable = Timetable(
        schoolClass = SchoolClassInfo(id = 1, name = "9А", timeZoneId = "Europe/Moscow"),
        days = listOf(
            SchoolDay(
                date = LocalDate.of(2026, 9, 21),
                weekday = 1,
                lessons = listOf(
                    Lesson(
                        index = 1,
                        subject = "Алгебра",
                        startsAt = LocalTime.of(8, 30),
                        endsAt = LocalTime.of(9, 15),
                    ),
                ),
            ),
        ),
    )

    private fun snapshot(mode: ShellMode, timetable: Timetable?) = snapshotOf(
        timetable = timetable,
        now = now,
        mode = mode,
        options = WidgetOptions(),
        language = AppLanguage.SYSTEM,
    )

    @Test
    fun `the snapshot carries the mode it was read in`() {
        for (mode in ShellMode.entries) {
            assertEquals(mode, snapshot(mode, timetable = null).mode)
        }
    }

    @Test
    fun `a class with a timetable draws its lesson, not an empty sentence`() {
        val snapshot = snapshot(ShellMode.CLASS, timetable)
        assertTrue("${snapshot.state}", snapshot.state is DayState.InLesson)
        assertEquals(1, snapshot.week.first().lessons)
    }

    @Test
    fun `outside a class nothing of a timetable is drawn, even one handed in`() {
        for (mode in listOf(ShellMode.NONE, ShellMode.DIARY)) {
            val snapshot = snapshot(mode, timetable)
            assertNull("$mode", snapshot.state)
            assertNull("$mode", snapshot.today)
            assertNull("$mode", snapshot.homeworkDay)
            assertEquals("$mode", List(7) { 0 }, snapshot.week.map { it.lessons })
        }
    }

    @Test
    fun `each mode with nothing to draw lands on its own sentence`() {
        val expected = mapOf(
            ShellMode.NONE to (R.string.widget_empty_short to R.string.widget_empty_title),
            ShellMode.CLASS to (R.string.widget_no_data_short to R.string.widget_no_data_title),
            ShellMode.DIARY to (R.string.widget_diary_only_short to R.string.widget_diary_only_title),
        )
        for ((mode, texts) in expected) {
            val snapshot = snapshot(mode, timetable = null)
            // A null state is what sends the body to its empty sentence.
            assertNull("$mode", snapshot.state)
            assertEquals("$mode, narrow", texts.first, emptyTextRes(snapshot.mode, narrow))
            assertEquals("$mode, large", texts.second, emptyTextRes(snapshot.mode, large))
        }
    }
}

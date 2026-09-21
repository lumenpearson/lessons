package com.lumenpearson.lessons.widget.ui

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.WidgetSizeClass
import com.lumenpearson.lessons.widget.format.WidgetStrings
import java.io.File
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Nothing drawn into the 110 dp column is longer than the column.
 *
 * Glance cannot ellipsize. A `Text` at `maxLines = 1` that does not fit is cut
 * at whatever pixel runs out — mid-word, with no «…» to say so — so a label one
 * word too long is not a cosmetic problem, it is a word that reads as a
 * different word.
 *
 * `RestDayBody` is the card this was got wrong on. It is reached through
 * `StackBody`, which serves `SMALL_TALL` and `NARROW` as well as the wide
 * rungs, and it drew the full state label: «УРОКИ ЗАКОНЧИЛИСЬ» and
 * «СОКРАЩЁННЫЙ ДЕНЬ» into about 90 dp of usable width. The reasoning that once
 * removed a blanket `stateLabelShort` as unreachable had looked at the two 2x1
 * layouts, which take the homework branch instead, and not at this one.
 *
 * Read out of the source tree rather than through a `Context`, which is how
 * `StaticLayoutColourTest` does it and the only way here: this module's unit
 * tests run with `isReturnDefaultValues`, so `getString` answers nothing.
 */
class NarrowLabelBudgetTest {

    private val folders = listOf("values", "values-en")

    private fun strings(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    /** The label each rest state is drawn with on a narrow rung, per language. */
    private fun narrowLabels(folder: String): Map<String, String> {
        val table = strings(folder)
        val states = listOf(DayState.AfterSchool(null, null, null)) +
            DayKind.entries.map {
                DayState.DayOff(
                    date = LocalDate.of(2026, 9, 21),
                    kind = it,
                    note = null,
                    homeworkDay = null,
                    validUntil = null,
                )
            }
        return states.associate { state ->
            val name = when {
                WidgetStrings.shortStateLabelRes(state, narrow = true) ==
                    R.string.widget_state_after_school_short -> "widget_state_after_school_short"
                WidgetStrings.shortStateLabelRes(state, narrow = true) ==
                    R.string.widget_state_shortened_short -> "widget_state_shortened_short"
                state is DayState.AfterSchool -> "widget_state_after_school"
                state is DayState.DayOff -> when (state.kind) {
                    DayKind.HOLIDAY -> "widget_state_holiday"
                    DayKind.REMOTE -> "widget_state_remote"
                    DayKind.SHORTENED -> "widget_state_shortened"
                    DayKind.NORMAL -> "widget_state_day_off"
                }
                else -> error("not a rest state: $state")
            }
            name to (table[name] ?: error("$folder has no $name"))
        }
    }

    @Test
    fun `every rest-day label fits the narrow column in both languages`() {
        for (folder in folders) {
            val over = narrowLabels(folder)
                .filterValues { it.length > WidgetStrings.NARROW_LABEL_CHARS }
            assertEquals("$folder draws these into 110 dp", emptyMap<String, String>(), over)
        }
    }

    @Test
    fun `the today-homework line fits beside its count in both languages`() {
        // The line is a Row: the title takes the weight and the count sits
        // beside it, so the title has less than the column, not all of it.
        // Russian abbreviates to «ДЗ»; English has no such word, and the
        // translation used to spell it out at eighteen characters.
        for (folder in folders) {
            val title = strings(folder).getValue("widget_homework_today_title")
            assertTrue(
                "«$title» is longer than the narrow column holds beside a count",
                title.length <= WidgetStrings.NARROW_LABEL_CHARS + 2,
            )
        }
    }

    @Test
    fun `a wide rung is still given the whole sentence`() {
        val afterSchool = DayState.AfterSchool(null, null, null)
        assertNull(WidgetStrings.shortStateLabelRes(afterSchool, narrow = false))
    }

    @Test
    fun `a narrow rung really does reach the card these are drawn on`() {
        // The budget above only means anything while a narrow rung takes the
        // `StackBody` path; `TINY` and `WIDE` have bodies of their own.
        val narrowStack = WidgetSizeClass.entries.filter {
            it.isNarrow && it != WidgetSizeClass.TINY && it != WidgetSizeClass.WIDE
        }
        assertTrue("no narrow rung reaches StackBody any more", narrowStack.isNotEmpty())
        assertTrue(
            "no narrow rung draws today's homework line any more",
            narrowStack.any { it.showsTodayHomework },
        )
    }
}

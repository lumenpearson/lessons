package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ImportConflict
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the management sheets make before anything leaves the phone.
 *
 * None of these is a permission check and none of them is authoritative — the
 * server validates all of it again. They are here because the alternatives are
 * worse in a way that is invisible: a form that lets through a name the server
 * will refuse turns a typo into a round trip and an English error message, and
 * a conflict summary that miscounts is how somebody agrees to overwrite a week
 * they meant to keep.
 */
class ManagementFormsTest {

    // -- the class card -----------------------------------------------------

    @Test
    fun `a class must have a name`() {
        assertEquals(FormProblem.NAME_BLANK, classFormProblem("", "", ""))
        assertEquals(FormProblem.NAME_BLANK, classFormProblem("   ", "Школа", "Москва"))
        assertNull(classFormProblem("9А", "", ""))
    }

    /** Blank school and city are not a problem: they are how «убрать» is said. */
    @Test
    fun `an empty school or city is a value, not a mistake`() {
        assertNull(classFormProblem("9А", "  ", "  "))
    }

    @Test
    fun `the server's own limits are the form's limits`() {
        assertEquals(FormProblem.NAME_TOO_LONG, classFormProblem("9".repeat(65), "", ""))
        assertNull(classFormProblem("9".repeat(64), "", ""))
        assertEquals(FormProblem.SCHOOL_TOO_LONG, classFormProblem("9А", "ш".repeat(201), ""))
        assertEquals(FormProblem.CITY_TOO_LONG, classFormProblem("9А", "", "г".repeat(121)))
    }

    // -- deleting the class -------------------------------------------------

    /**
     * The confirmation the endpoint insists on. The comparison is exact after
     * trimming, exactly as the server's is, so a lower-case near-miss does not
     * delete anything.
     */
    @Test
    fun `only the class's own name confirms deleting it`() {
        assertTrue(confirmsClassName("9А", "9А"))
        assertTrue(confirmsClassName("  9А  ", "9А"))
        assertFalse(confirmsClassName("9а", "9А"))
        assertFalse(confirmsClassName("", "9А"))
        assertFalse(confirmsClassName("9А ещё", "9А"))
        assertEquals(FormProblem.NAME_DOES_NOT_MATCH, deleteConfirmProblem("9а", "9А"))
        assertNull(deleteConfirmProblem("9А", "9А"))
    }

    /** A class with no name could be confirmed by an empty box; it cannot. */
    @Test
    fun `an unnamed class cannot be confirmed by typing nothing`() {
        assertFalse(confirmsClassName("", ""))
    }

    // -- subjects -----------------------------------------------------------

    @Test
    fun `a subject must have a name and may have nothing else`() {
        assertEquals(FormProblem.NAME_BLANK, subjectFormProblem("", "", "", ""))
        assertNull(subjectFormProblem("Алгебра", "", "", ""))
    }

    @Test
    fun `the short name, the teacher and the colour have their own limits`() {
        assertEquals(
            FormProblem.SHORT_NAME_TOO_LONG,
            subjectFormProblem("Алгебра", "а".repeat(17), "", ""),
        )
        assertNull(subjectFormProblem("Алгебра", "а".repeat(16), "", ""))
        assertEquals(
            FormProblem.TEACHER_TOO_LONG,
            subjectFormProblem("Алгебра", "", "и".repeat(121), ""),
        )
        assertEquals(
            FormProblem.COLOUR_UNREADABLE,
            subjectFormProblem("Алгебра", "", "", "синий"),
        )
    }

    /**
     * The same normalisation `_clean_colour` does on the server.
     *
     * Done here too so the form shows the colour that will be stored: without
     * it an admin types six hex digits, sees nothing change, and types them
     * again.
     */
    @Test
    fun `a colour is six hex digits, however they are typed`() {
        assertEquals("#5B6ABF", normaliseSubjectColour("#5b6abf"))
        assertEquals("#5B6ABF", normaliseSubjectColour("5B6ABF"))
        assertEquals("#5B6ABF", normaliseSubjectColour("  #5b6ABF  "))
    }

    /** Blank, a hyphen and an em dash all mean «убрать цвет». */
    @Test
    fun `three spellings of no colour all clear it`() {
        assertNull(normaliseSubjectColour(""))
        assertNull(normaliseSubjectColour("   "))
        assertNull(normaliseSubjectColour("-"))
        assertNull(normaliseSubjectColour("—"))
        assertNull(normaliseSubjectColour(null))
        // …and all of them are an acceptable value for the form.
        assertNull(subjectFormProblem("Алгебра", "", "", "—"))
    }

    @Test
    fun `a colour that is not six hex digits is refused rather than guessed at`() {
        assertEquals(FormProblem.COLOUR_UNREADABLE, subjectFormProblem("А", "", "", "#5b6ab"))
        assertEquals(FormProblem.COLOUR_UNREADABLE, subjectFormProblem("А", "", "", "#5b6abfg"))
        assertEquals(FormProblem.COLOUR_UNREADABLE, subjectFormProblem("А", "", "", "rgb(1,2,3)"))
    }

    // -- bells --------------------------------------------------------------

    @Test
    fun `a bell schedule needs a name`() {
        assertEquals(FormProblem.NAME_BLANK, bellScheduleNameProblem("  "))
        assertEquals(FormProblem.NAME_TOO_LONG, bellScheduleNameProblem("з".repeat(65)))
        assertNull(bellScheduleNameProblem("Сокращённое"))
    }

    /** «Пустой присылкой звонки не стереть» — the same rule, before the request. */
    @Test
    fun `an empty schedule is refused here as it is there`() {
        assertEquals(FormProblem.NO_PERIODS, bellPeriodsProblem(emptyList()))
    }

    @Test
    fun `a well-formed schedule passes`() {
        assertNull(
            bellPeriodsProblem(
                listOf(
                    period(1, "08:30", "09:15"),
                    period(2, "09:25", "10:10"),
                ),
            ),
        )
    }

    @Test
    fun `lesson numbers must not repeat`() {
        assertEquals(
            FormProblem.PERIOD_NUMBER_REPEATED,
            bellPeriodsProblem(listOf(period(1, "08:30", "09:15"), period(1, "09:25", "10:10"))),
        )
    }

    @Test
    fun `a lesson cannot end before it starts, or at the same moment`() {
        assertEquals(
            FormProblem.PERIOD_ENDS_BEFORE_IT_STARTS,
            bellPeriodsProblem(listOf(period(1, "09:15", "08:30"))),
        )
        assertEquals(
            FormProblem.PERIOD_ENDS_BEFORE_IT_STARTS,
            bellPeriodsProblem(listOf(period(1, "08:30", "08:30"))),
        )
    }

    @Test
    fun `there are twenty lessons at most and they are numbered one to twenty`() {
        val twentyOne = (1..21).map { period(it, "08:00", "08:30") }
        assertEquals(FormProblem.TOO_MANY_PERIODS, bellPeriodsProblem(twentyOne))
        assertEquals(
            FormProblem.PERIOD_NUMBER_OUT_OF_RANGE,
            bellPeriodsProblem(listOf(period(0, "08:30", "09:15"))),
        )
        assertEquals(
            FormProblem.PERIOD_NUMBER_OUT_OF_RANGE,
            bellPeriodsProblem(listOf(period(21, "08:30", "09:15"))),
        )
    }

    // -- pasting a timetable ------------------------------------------------

    @Test
    fun `a paste must have something in it`() {
        assertEquals(FormProblem.EMPTY_PASTE, pasteProblem(""))
        assertEquals(FormProblem.EMPTY_PASTE, pasteProblem("\n \n"))
        assertEquals(FormProblem.PASTE_TOO_LONG, pasteProblem("x".repeat(20_001)))
        assertNull(pasteProblem("== Понедельник ==\n1. Алгебра, 214"))
    }

    // -- what an import would overwrite -------------------------------------

    @Test
    fun `nothing collided is an empty summary`() {
        val summary = summariseImportConflicts(emptyList())
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.days)
        assertEquals(0, summary.existing)
    }

    /**
     * The numbers an admin decides on. [ImportConflictSummary.existing] is the
     * one that matters: it is how many lessons disappear if they press
     * «Заменить», on days they are not looking at.
     */
    @Test
    fun `the summary counts the days and both sides of the trade`() {
        val summary = summariseImportConflicts(
            listOf(
                ImportConflict(weekday = 3, existing = 5, incoming = 4),
                ImportConflict(weekday = 1, existing = 3, incoming = 2),
            ),
        )
        assertEquals(2, summary.days)
        assertEquals(8, summary.existing)
        assertEquals(6, summary.incoming)
    }

    /** Monday to Sunday, whatever order the server happened to send. */
    @Test
    fun `the lines are sorted by weekday`() {
        val summary = summariseImportConflicts(
            listOf(
                ImportConflict(weekday = 5, existing = 1, incoming = 1),
                ImportConflict(weekday = 1, existing = 1, incoming = 1),
                ImportConflict(weekday = 3, existing = 1, incoming = 1),
            ),
        )
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            summary.lines.map { it.weekday },
        )
    }

    /**
     * A weekday number the server does not send is still a conflict, and
     * hiding it would be the one thing this screen must never do. It sorts
     * last and keeps its number so the sheet can name it somehow.
     */
    @Test
    fun `an unnameable weekday is kept and sorted last`() {
        val summary = summariseImportConflicts(
            listOf(
                ImportConflict(weekday = 9, existing = 2, incoming = 1),
                ImportConflict(weekday = 2, existing = 1, incoming = 1),
            ),
        )
        assertEquals(2, summary.days)
        assertEquals(DayOfWeek.TUESDAY, summary.lines.first().weekday)
        assertNull(summary.lines.last().weekday)
        assertEquals(9, summary.lines.last().number)
        // …and it is still counted, so the total is honest.
        assertEquals(3, summary.existing)
    }

    @Test
    fun `weekdays are one for Monday through seven for Sunday`() {
        assertEquals(DayOfWeek.MONDAY, weekdayOrNull(1))
        assertEquals(DayOfWeek.SUNDAY, weekdayOrNull(7))
        assertNull(weekdayOrNull(0))
        assertNull(weekdayOrNull(8))
    }

    // -- time zones ---------------------------------------------------------

    @Test
    fun `the eleven Russian zones are offered`() {
        assertEquals(11, RussianTimeZones.size)
        assertEquals(11, zoneOptions("Europe/Moscow").size)
        assertTrue(RussianTimeZones.any { it.id == "Europe/Moscow" })
        assertTrue(RussianTimeZones.any { it.id == "Asia/Kamchatka" })
    }

    /**
     * A class configured by hand to a zone outside Russia keeps it.
     *
     * A picker that dropped it would offer an admin a list their own class is
     * not in, and moving off it would be one tap with no way back.
     */
    @Test
    fun `a zone the list does not have is put in front of it`() {
        val options = zoneOptions("Europe/Berlin")
        assertEquals(12, options.size)
        assertEquals("Europe/Berlin", options.first().id)
    }

    @Test
    fun `no zone at all is just the eleven`() {
        assertEquals(11, zoneOptions(null).size)
        assertEquals(11, zoneOptions("  ").size)
    }

    // -- who may grant what -------------------------------------------------

    /**
     * Nobody may grant a role at or above their own, and OWNER is never
     * grantable through this endpoint at all — the deployment's `OWNER_IDS` is
     * the only source of it. The server checks both again.
     */
    @Test
    fun `an administrator may grant below themselves and no higher`() {
        assertEquals(
            listOf(ClassRole.VIEWER, ClassRole.EDITOR),
            grantableRoles(ClassRole.ADMIN),
        )
    }

    @Test
    fun `an owner may grant up to administrator, never owner`() {
        assertEquals(
            listOf(ClassRole.VIEWER, ClassRole.EDITOR, ClassRole.ADMIN),
            grantableRoles(ClassRole.OWNER),
        )
        assertFalse(grantableRoles(ClassRole.OWNER).contains(ClassRole.OWNER))
    }

    @Test
    fun `an editor and a viewer may grant nothing`() {
        assertTrue(grantableRoles(ClassRole.EDITOR).isEmpty())
        assertTrue(grantableRoles(ClassRole.VIEWER).isEmpty())
    }

    /** The state before `/me` answers offers nothing, as [isClassManager] does. */
    @Test
    fun `an unanswered role grants nothing`() {
        assertTrue(grantableRoles(null).isEmpty())
    }

    private fun period(index: Int, start: String, end: String) = BellPeriod(
        index = index,
        startsAt = LocalTime.parse(start),
        endsAt = LocalTime.parse(end),
    )
}

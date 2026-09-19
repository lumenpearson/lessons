package com.lumenpearson.lessons.widget.ui

import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.widget.WidgetOptions
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one trailing detail a timeline row has room for.
 *
 * A phone-width row holds a time, a subject and one thing after it, so the two
 * candidates — the room and the teacher — are wired as a single setting: the
 * widget turns «Показывать учителя» on and the room off together. The rule that
 * decides between them is here rather than inside the composable because it is
 * the part that can be wrong, and a Glance composable cannot be asked anything
 * without a launcher.
 */
class TimelineRowDetailTest {

    private fun lesson(room: String? = null, teacher: String? = null, replaced: Boolean = false) =
        Lesson(
            index = 1,
            subject = "Алгебра",
            startsAt = LocalTime.of(8, 30),
            endsAt = LocalTime.of(9, 15),
            room = room,
            teacher = teacher,
            isReplaced = replaced,
        )

    /** How the widget wires «Показывать учителя», which is on by default. */
    private val teacherChosen = WidgetOptions(showTeacher = true, showRoom = false)

    private val roomChosen = WidgetOptions(showTeacher = false, showRoom = true)

    @Test
    fun `the chosen detail is the one drawn`() {
        assertEquals(
            RowDetail.TEACHER,
            rowDetailFor(lesson(room = "214", teacher = "Иванова М. П."), teacherChosen, compact = false),
        )
        assertEquals(
            RowDetail.ROOM,
            rowDetailFor(lesson(room = "214", teacher = "Иванова М. П."), roomChosen, compact = false),
        )
    }

    /**
     * The defect this exists for. With the teacher chosen the room is switched
     * off, so a lesson the timetable carries a room for and no teacher — which
     * is most of them, because a teacher is optional in the paste grammar and in
     * a substitution — lost the room and put nothing in its place: the widest column
     * of the row went blank while the cache held the answer.
     */
    @Test
    fun `a lesson with a room and no teacher still shows the room`() {
        assertEquals(
            RowDetail.ROOM,
            rowDetailFor(lesson(room = "214", teacher = null), teacherChosen, compact = false),
        )
        assertEquals(
            RowDetail.ROOM,
            rowDetailFor(lesson(room = "214", teacher = "   "), teacherChosen, compact = false),
        )
    }

    @Test
    fun `nothing is invented for a lesson that carries neither`() {
        assertEquals(RowDetail.NONE, rowDetailFor(lesson(), teacherChosen, compact = false))
        assertEquals(RowDetail.NONE, rowDetailFor(lesson(), roomChosen, compact = false))
    }

    /** «Замена» is the reason to read the row at all, so it takes the slot. */
    @Test
    fun `a substitution outranks both`() {
        assertEquals(
            RowDetail.REPLACED,
            rowDetailFor(lesson(room = "214", teacher = "Иванова", replaced = true), teacherChosen, compact = false),
        )
        assertEquals(
            RowDetail.REPLACED,
            rowDetailFor(lesson(replaced = true), roomChosen, compact = true),
        )
    }

    /** 110 dp holds a time and a subject and nothing else. */
    @Test
    fun `the narrow column keeps its subject`() {
        assertEquals(
            RowDetail.NONE,
            rowDetailFor(lesson(room = "214", teacher = "Иванова"), teacherChosen, compact = true),
        )
    }
}

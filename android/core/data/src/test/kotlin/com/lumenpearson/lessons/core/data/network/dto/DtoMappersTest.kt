package com.lumenpearson.lessons.core.data.network.dto

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Wire -> domain mapping, the one piece of this module that can be tested
 * without an emulator and the one most likely to be broken by a server change.
 */
class DtoMappersTest {

    @Test
    fun normalDay_mapsEveryFieldAndSortsTheTimeline() {
        val dto = DayDto(
            date = "2026-09-09",
            weekday = 3,
            kind = "NORMAL",
            // Deliberately out of order: sorting is the data layer's job.
            lessons = listOf(
                LessonDto(
                    index = 2,
                    subject = "Физика",
                    startsAt = "09:25:00",
                    endsAt = "10:10:00",
                    room = "301",
                    teacher = "Петров П.П.",
                ),
                LessonDto(
                    index = 1,
                    subject = "Алгебра",
                    startsAt = "08:30:00",
                    endsAt = "09:15:00",
                    room = "204",
                    teacher = "Иванова И.И.",
                    color = "#FF8A65",
                    note = "   ",
                ),
            ),
            events = listOf(
                EventDto(
                    title = "Столовая",
                    kind = "CANTEEN",
                    startsAt = "10:10:00",
                    endsAt = "10:30:00",
                    location = "1 этаж",
                ),
            ),
            homework = listOf(
                HomeworkDto(
                    subject = "Алгебра",
                    text = "№ 245-247",
                    attachmentUrl = "https://school.example/hw/245.pdf",
                ),
            ),
            note = "Классный час после уроков",
        )

        val day = requireNotNull(dto.toDomain())

        assertEquals(LocalDate.of(2026, 9, 9), day.date)
        assertEquals(3, day.weekday)
        assertEquals(DayKind.NORMAL, day.kind)
        assertEquals("Классный час после уроков", day.note)

        assertEquals(listOf("Алгебра", "Физика"), day.lessons.map { it.subject })
        val algebra = day.lessons.first()
        assertEquals(1, algebra.index)
        assertEquals(LocalTime.of(8, 30), algebra.startsAt)
        assertEquals(LocalTime.of(9, 15), algebra.endsAt)
        assertEquals("204", algebra.room)
        assertEquals("Иванова И.И.", algebra.teacher)
        assertEquals("#FF8A65", algebra.colorHex)
        // A whitespace-only note is "not set", not a note made of spaces.
        assertNull(algebra.note)
        assertFalse(algebra.isReplaced)
        assertFalse(algebra.isCancelled)

        val canteen = day.events.single()
        assertEquals(EventKind.CANTEEN, canteen.kind)
        assertEquals(LocalTime.of(10, 10), canteen.startsAt)
        assertEquals("1 этаж", canteen.location)
        assertFalse(canteen.coversLesson)

        val homework = day.homework.single()
        assertEquals("Алгебра", homework.subject)
        assertEquals("№ 245-247", homework.text)
        assertEquals("https://school.example/hw/245.pdf", homework.attachmentUrl)
    }

    @Test
    fun cancelledAndReplacedLessons_keepTheirFlagsAndStayInTheDay() {
        val dto = DayDto(
            date = "2026-09-10",
            weekday = 4,
            kind = "NORMAL",
            lessons = listOf(
                LessonDto(
                    index = 1,
                    subject = "История",
                    startsAt = "08:30:00",
                    endsAt = "09:15:00",
                    isCancelled = true,
                    note = "Учитель болеет",
                ),
                LessonDto(
                    index = 2,
                    subject = "Информатика",
                    startsAt = "09:25:00",
                    endsAt = "10:10:00",
                    room = "Каб. 12",
                    isReplaced = true,
                ),
            ),
        )

        val day = requireNotNull(dto.toDomain())

        // Both rows survive: a cancelled lesson still has to be drawn, struck
        // through, so the pupil knows not to come.
        assertEquals(2, day.lessons.size)
        val cancelled = day.lessons.first { it.subject == "История" }
        assertTrue(cancelled.isCancelled)
        assertFalse(cancelled.isReplaced)
        assertEquals("Учитель болеет", cancelled.note)

        val replaced = day.lessons.first { it.subject == "Информатика" }
        assertTrue(replaced.isReplaced)
        assertFalse(replaced.isCancelled)

        // ...but only the ones that actually happen count as the day's timeline.
        assertEquals(listOf("Информатика"), day.activeLessons.map { it.subject })
        assertTrue(day.hasLessons)
    }

    @Test
    fun unknownKinds_fallBackInsteadOfFailing() {
        val dto = DayDto(
            date = "2026-09-11",
            // Missing on the wire: derived from the date rather than left at 0.
            weekday = 0,
            kind = "QUARANTINE",
            lessons = listOf(
                LessonDto(index = 1, subject = "Химия", startsAt = "08:30:00", endsAt = "09:15:00"),
            ),
            events = listOf(
                EventDto(
                    title = "Олимпиада",
                    kind = "OLYMPIAD",
                    startsAt = "12:00:00",
                    endsAt = "14:00:00",
                    coversLesson = true,
                ),
            ),
        )

        val day = requireNotNull(dto.toDomain())

        assertEquals(DayKind.NORMAL, day.kind)
        assertEquals(EventKind.EVENT, day.events.single().kind)
        assertTrue(day.events.single().coversLesson)
        // 2026-09-11 is a Friday.
        assertEquals(5, day.weekday)
        // The unknown kinds cost nothing else: the rest of the day is intact.
        assertEquals(listOf("Химия"), day.lessons.map { it.subject })
    }

    @Test
    fun emptyDay_mapsToADayWithNothingOnIt() {
        val dto = DayDto(
            date = "2026-09-12",
            weekday = 6,
            kind = "HOLIDAY",
            note = "День города",
        )

        val day = requireNotNull(dto.toDomain())

        assertEquals(LocalDate.of(2026, 9, 12), day.date)
        assertEquals(DayKind.HOLIDAY, day.kind)
        assertTrue(day.lessons.isEmpty())
        assertTrue(day.events.isEmpty())
        assertTrue(day.homework.isEmpty())
        assertFalse(day.hasLessons)
        assertNull(day.firstLesson)
        assertEquals("День города", day.note)
    }

    @Test
    fun unreadableValues_dropOnlyWhatTheyBreak() {
        val dto = DayDto(
            date = "2026-09-14",
            weekday = 1,
            kind = "NORMAL",
            lessons = listOf(
                LessonDto(index = 1, subject = "Геометрия", startsAt = "не задано", endsAt = "09:15:00"),
                LessonDto(index = 2, subject = "Биология", startsAt = "09:25", endsAt = "10:10:00.000"),
            ),
        )

        val day = requireNotNull(dto.toDomain())

        // The broken lesson is gone, the tolerant time formats are kept.
        assertEquals(listOf("Биология"), day.lessons.map { it.subject })
        assertEquals(LocalTime.of(9, 25), day.lessons.single().startsAt)
        assertEquals(LocalTime.of(10, 10), day.lessons.single().endsAt)

        // A day without a usable date has nowhere to be stored, so it is dropped.
        assertNull(DayDto(date = "", weekday = 1).toDomain())
    }

    @Test
    fun bundle_mapsClass_days_andTheLookaheadDay() {
        val bundle = BundleDto(
            apiVersion = 1,
            schoolClass = SchoolClassDto(
                id = 7,
                name = "7 Б",
                school = "  ",
                timezone = "Mars/Olympus",
            ),
            generatedAt = "2026-09-09T07:45:00+03:00",
            days = listOf(
                DayDto(date = "2026-09-10", weekday = 4, kind = "NORMAL"),
                DayDto(date = "2026-09-09", weekday = 3, kind = "NORMAL"),
                DayDto(date = "сегодня", weekday = 3, kind = "NORMAL"),
            ),
            nextSchoolDay = DayDto(
                date = "2026-09-28",
                weekday = 1,
                kind = "NORMAL",
                lessons = listOf(
                    LessonDto(index = 1, subject = "Алгебра", startsAt = "08:30:00", endsAt = "09:15:00"),
                ),
            ),
        )

        val timetable = bundle.toDomain(fallbackSyncedAtEpochMillis = 42L)

        assertEquals(7L, timetable.schoolClass.id)
        assertEquals("7 Б", timetable.schoolClass.name)
        assertNull(timetable.schoolClass.school)
        // An unusable zone id must not poison every later time calculation.
        assertEquals("Europe/Moscow", timetable.schoolClass.timeZoneId)

        // The undated day is dropped and the rest come back in calendar order.
        assertEquals(
            listOf(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10)),
            timetable.days.map { it.date },
        )

        val lookahead = requireNotNull(timetable.nextSchoolDay)
        assertEquals(LocalDate.of(2026, 9, 28), lookahead.date)
        assertTrue(lookahead.hasLessons)
        // ...and it is reachable through the domain helper the widget uses.
        assertEquals(lookahead.date, timetable.schoolDayAfter(LocalDate.of(2026, 9, 10))?.date)

        // 2026-09-09T07:45+03:00 == 2026-09-09T04:45Z.
        assertEquals(
            OffsetDateTime.parse("2026-09-09T07:45:00+03:00").toInstant().toEpochMilli(),
            timetable.syncedAtEpochMillis,
        )
    }

    @Test
    fun bundle_withoutGeneratedAt_usesTheFallbackClock() {
        val bundle = BundleDto(
            schoolClass = SchoolClassDto(id = 1, name = "1 А", timezone = "Europe/Moscow"),
            generatedAt = "",
        )

        assertEquals(1_700_000_000_000L, bundle.toDomain(1_700_000_000_000L).syncedAtEpochMillis)
        assertTrue(bundle.toDomain(0L).days.isEmpty())
        assertNull(bundle.toDomain(0L).nextSchoolDay)
    }
}

package com.lumenpearson.lessons.core.data.network.dto

import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.data.repository.toDomain
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes `/api/v1/diary` payloads shaped exactly like FastAPI's, with the
 * production [NetworkModule.json] configuration.
 *
 * The `@SerialName` values asserted here are copied from the `Diary*` models in
 * `server/app/schemas.py` rather than from the Kotlin property names, which is
 * what makes this the test that fails when a field is renamed on one side of
 * the repository only. Every payload carries at least one explicit `null` and
 * one field the client has never heard of, because both are things the server
 * is allowed to send.
 */
class DiaryJsonTest {

    private val json = NetworkModule.json()

    @Test
    fun `a login answer decodes`() {
        val decoded = json.decodeFromString<DiaryLoginResponseDto>(
            """{ "token": "abc.def", "login": "parent@example.com", "issued_at": "later" }""",
        )

        assertEquals("abc.def", decoded.token)
        assertEquals("parent@example.com", decoded.login)
    }

    @Test
    fun `students decode with a missing middle name and an unknown field`() {
        val decoded = json.decodeFromString<List<DiaryStudentDto>>(
            """
            [
              {
                "id": 4152,
                "first_name": "Иван",
                "last_name": "Иванов",
                "middle_name": "Петрович",
                "full_name": "Иванов Иван Петрович",
                "school": "ГБОУ СОШ № 1",
                "class_name": "7 Б",
                "education_id": "never sent, never read"
              },
              {
                "id": 4153,
                "first_name": "Мария",
                "last_name": "Иванова",
                "middle_name": null,
                "full_name": "Иванова Мария",
                "school": null,
                "class_name": null
              }
            ]
            """.trimIndent(),
        )

        assertEquals(2, decoded.size)
        val first = decoded[0].toDomain()
        assertEquals(4152L, first.id)
        assertEquals("Иванов Иван Петрович", first.fullName)
        assertEquals("7 Б", first.className)
        // The picker's label: surname and name, nothing else.
        assertEquals("Иванов Иван", first.shortName)

        val second = decoded[1].toDomain()
        assertNull(second.middleName)
        assertNull(second.school)
        assertNull(second.className)
    }

    @Test
    fun `a lesson decodes with its times, and one without them survives`() {
        val decoded = json.decodeFromString<List<DiaryLessonDto>>(
            """
            [
              {
                "date": "2026-09-14",
                "number": 2,
                "subject": "Алгебра",
                "starts_at": "09:25:00",
                "ends_at": "10:10:00",
                "room": "204",
                "teacher": "Иванова И.И.",
                "homework": "§12, № 4-7",
                "topic": "Квадратные уравнения",
                "lesson_uid": "unknown to this client"
              },
              {
                "date": "2026-09-14",
                "number": null,
                "subject": "Классный час",
                "starts_at": null,
                "ends_at": null,
                "room": "   ",
                "teacher": null,
                "homework": null,
                "topic": null
              }
            ]
            """.trimIndent(),
        )

        val timed = decoded[0].toDomain()!!
        assertEquals(LocalDate.of(2026, 9, 14), timed.date)
        assertEquals(2, timed.number)
        assertEquals(LocalTime.of(9, 25), timed.startsAt)
        assertEquals(LocalTime.of(10, 10), timed.endsAt)
        assertEquals("§12, № 4-7", timed.homework)

        // A lesson with no bell time is still a lesson: dropping it would
        // misreport the day it belongs to.
        val untimed = decoded[1].toDomain()!!
        assertNull(untimed.startsAt)
        assertNull(untimed.number)
        // "   " is the same nothing as null, and is read as one.
        assertNull(untimed.room)
    }

    @Test
    fun `a lesson with an unreadable date is dropped rather than guessed`() {
        val decoded = json.decodeFromString<DiaryLessonDto>(
            """{ "date": "14.09.2026", "subject": "Физика" }""",
        )

        assertNull(decoded.toDomain())
    }

    @Test
    fun `homework decodes and an empty assignment is dropped`() {
        val decoded = json.decodeFromString<List<DiaryHomeworkDto>>(
            """
            [
              {
                "id": 9001,
                "due_date": "2026-09-15",
                "subject": "Физика",
                "text": "Прочитать § 5",
                "teacher": "Петров П.П."
              },
              { "id": null, "due_date": "2026-09-16", "subject": "Химия", "text": "   " }
            ]
            """.trimIndent(),
        )

        val real = decoded[0].toDomain()!!
        assertEquals(LocalDate.of(2026, 9, 15), real.dueDate)
        assertEquals("Прочитать § 5", real.text)
        assertEquals("Петров П.П.", real.teacher)

        assertNull(decoded[1].toDomain())
    }

    @Test
    fun `every kind of register entry decodes as the kind it is`() {
        val decoded = json.decodeFromString<List<DiaryMarkDto>>(
            """
            [
              {
                "id": 1,
                "subject_id": 21,
                "subject": "Алгебра",
                "date": "2026-09-11",
                "value": "5",
                "kind": "grade",
                "reason": null,
                "comment": "Контрольная работа"
              },
              {
                "id": 2,
                "subject": "Алгебра",
                "date": "2026-09-12",
                "value": "Н",
                "kind": "absence",
                "reason": "Болезнь"
              },
              { "subject": "Физика", "date": null, "value": "4", "kind": "grade" },
              { "subject": "Физика", "value": "Оп", "kind": "late" },
              { "subject": "Физика", "value": "!", "kind": "remark" },
              { "subject": "Физика", "value": "зач", "kind": "something-new-upstream" }
            ]
            """.trimIndent(),
        )

        val marks = decoded.mapNotNull { it.toDomain() }
        assertEquals(6, marks.size)
        assertEquals(DiaryMarkKind.GRADE, marks[0].kind)
        assertEquals(5, marks[0].numericValue)
        assertEquals("Контрольная работа", marks[0].comment)

        assertEquals(DiaryMarkKind.ABSENCE, marks[1].kind)
        assertEquals("Болезнь", marks[1].reason)
        // The whole point of `kind`: "Н" is not a mark and has no number in it.
        assertNull(marks[1].numericValue)

        // A term mark carries no date, and that is not a reason to drop it.
        assertNull(marks[2].date)
        assertEquals(4, marks[2].numericValue)

        assertEquals(DiaryMarkKind.LATE, marks[3].kind)
        assertEquals(DiaryMarkKind.REMARK, marks[4].kind)
        // An unknown kind from a newer server is "other", never a crash.
        assertEquals(DiaryMarkKind.OTHER, marks[5].kind)
    }

    /**
     * A corrected lesson and one from a server that has never heard of
     * corrections, in the same payload. The second is the case that matters:
     * the app is shipped to phones that will keep talking to whatever is
     * deployed, and a missing `target` has to read as "nothing is corrected
     * here" rather than as a payload that will not decode.
     */
    @Test
    fun `a lesson carries its corrections, and an older server's carries none`() {
        val decoded = json.decodeFromString<List<DiaryLessonDto>>(
            """
            [
              {
                "date": "2026-09-14",
                "number": 2,
                "subject": "Алгебра",
                "room": "301",
                "teacher": "Иванова И.И.",
                "target": "lesson:2026-09-14:n2:Алгебра",
                "edits": [
                  {
                    "field": "room",
                    "value": "301",
                    "original": "204",
                    "changed_upstream": true
                  },
                  { "field": "   ", "value": "уже не поле", "original": null }
                ],
                "ambiguous": false
              },
              {
                "date": "2026-09-14",
                "number": 3,
                "subject": "Физика"
              },
              {
                "date": "2026-09-14",
                "number": 4,
                "subject": "Физкультура",
                "target": "lesson:2026-09-14:n4:Физкультура",
                "edits": [],
                "ambiguous": true
              }
            ]
            """.trimIndent(),
        )

        val corrected = decoded[0].toDomain()!!
        assertEquals("lesson:2026-09-14:n2:Алгебра", corrected.target)
        // The value arrives already corrected: the server lays it over on the
        // way out, so the client never applies anything itself.
        assertEquals("301", corrected.room)
        // One edit, not two: the second names no field, so nothing could draw
        // it and nothing could reset it.
        assertEquals(1, corrected.edits.size)
        assertEquals("room", corrected.edits.first().field)
        assertEquals("204", corrected.edits.first().original)
        assertTrue(corrected.edits.first().changedUpstream)

        val untouched = decoded[1].toDomain()!!
        assertEquals("", untouched.target)
        assertTrue(untouched.edits.isEmpty())
        assertEquals(false, untouched.ambiguous)

        // A correction exists for this key and is deliberately not applied:
        // another lesson the same day shares it.
        assertTrue(decoded[2].toDomain()!!.ambiguous)
    }

    @Test
    fun `homework carries its corrections`() {
        val decoded = json.decodeFromString<List<DiaryHomeworkDto>>(
            """
            [
              {
                "id": 9001,
                "due_date": "2026-09-15",
                "subject": "Физика",
                "text": "Прочитать § 5 и § 6",
                "target": "hw:id:9001",
                "edits": [
                  {
                    "field": "text",
                    "value": "Прочитать § 5 и § 6",
                    "original": "Прочитать § 5",
                    "changed_upstream": false
                  }
                ]
              },
              { "id": null, "due_date": "2026-09-16", "subject": "Химия", "text": "§ 3" }
            ]
            """.trimIndent(),
        )

        val corrected = decoded[0].toDomain()!!
        assertEquals("hw:id:9001", corrected.target)
        assertEquals("Прочитать § 5 и § 6", corrected.text)
        assertEquals("Прочитать § 5", corrected.edits.single().original)
        assertEquals(false, corrected.edits.single().changedUpstream)

        // Keyed by day and subject when the upstream gave it no id.
        val plain = decoded[1].toDomain()!!
        assertEquals("", plain.target)
        assertTrue(plain.edits.isEmpty())
    }

    /**
     * The list a family resets from. A row naming a field this build cannot act
     * on is dropped rather than shown with a button that would send nothing.
     */
    @Test
    fun `stored corrections decode, and one this build cannot name is dropped`() {
        val decoded = json.decodeFromString<List<DiaryOverrideDto>>(
            """
            [
              {
                "target": "lesson:2026-09-14:n2:Алгебра",
                "field": "room",
                "value": "301",
                "original": "204",
                "updated_at": "2026-09-14T18:20:00+03:00"
              },
              {
                "target": "hw:id:9001",
                "field": "text",
                "value": "Прочитать § 5 и § 6",
                "original": null,
                "updated_at": "2026-09-14T18:21:00+03:00"
              },
              {
                "target": "lesson:2026-09-14:n5:Обед",
                "field": "canteen",
                "value": "нет",
                "updated_at": "2026-09-14T18:22:00+03:00"
              }
            ]
            """.trimIndent(),
        )

        val rows = decoded.mapNotNull { it.toDomain() }
        assertEquals(2, rows.size)
        assertEquals(DiaryField.ROOM, rows[0].field)
        assertEquals("lesson:2026-09-14:n2:Алгебра", rows[0].target)
        assertEquals("204", rows[0].original)
        assertEquals(DiaryField.TEXT, rows[1].field)
        assertNull(rows[1].original)
    }

    @Test
    fun `periods decode with their dates`() {
        val decoded = json.decodeFromString<List<DiaryPeriodDto>>(
            """
            [
              {
                "id": 7,
                "name": "1 четверть",
                "starts_on": "2026-09-01",
                "ends_on": "2026-10-25",
                "is_current": true
              },
              { "id": 8, "name": "2 четверть", "starts_on": null, "ends_on": null }
            ]
            """.trimIndent(),
        )

        val current = decoded[0].toDomain()
        assertTrue(current.isCurrent)
        assertEquals(LocalDate.of(2026, 9, 1), current.startsOn)
        assertEquals(LocalDate.of(2026, 10, 25), current.endsOn)

        val next = decoded[1].toDomain()
        assertNull(next.startsOn)
        // Absent `is_current` is false, not a failure to decode.
        assertEquals(false, next.isCurrent)
    }
}

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
    fun `a registration answer decodes, zone and pupils included`() {
        val decoded = json.decodeFromString<DiarySessionResponseDto>(
            """
            {
              "token": "abc.def", "login": "ivanova", "provider": "netschool",
              "region": "samara", "school_id": 1234, "school_name": null,
              "zone": "Europe/Samara", "issued_at": "later",
              "students": [{ "id": 7, "first_name": "Пётр", "last_name": "Иванов",
                             "full_name": "Иванов Пётр" }]
            }
            """.trimIndent(),
        )

        assertEquals("abc.def", decoded.token)
        assertEquals("ivanova", decoded.login)
        assertEquals("samara", decoded.region)
        assertEquals(1234L, decoded.schoolId)
        assertNull(decoded.schoolName)
        assertEquals("Europe/Samara", decoded.zone)
        assertEquals(listOf(7L), decoded.students.map { it.id })
    }

    /** A data class would print the bearer into any log that printed the answer. */
    @Test
    fun `a registration answer never prints its token`() {
        val decoded = DiarySessionResponseDto(token = "secret-bearer", login = "ivanova")

        assertTrue("secret-bearer" !in decoded.toString())
    }

    @Test
    fun `capabilities decode, the regions a server keeps sessions for included`() {
        val decoded = json.decodeFromString<DiaryCapabilitiesDto>(
            """
            { "enabled": true, "registration": true, "later": 1,
              "providers": { "petersburg": {}, "netschool": { "regions": ["samara", "tomsk"] } } }
            """.trimIndent(),
        )

        assertTrue(decoded.enabled)
        assertTrue(decoded.registration)
        assertTrue(decoded.providers.petersburg != null)
        assertEquals(listOf("samara", "tomsk"), decoded.providers.netschool?.regions)
    }

    /**
     * The registration body the server takes, field for field. Petersburg's
     * half of the union has no `region` or `school_id`, and the server forbids
     * unknown keys — so a `null` written out for either would be a `422`.
     */
    @Test
    fun `a Petersburg registration body carries no region or school at all`() {
        val body = DiarySessionRequestDto(
            provider = "petersburg",
            login = "parent@example.com",
            credential = json.encodeToJsonElement(PetersburgCredentialDto.serializer(), PetersburgCredentialDto("a.b.c"))
                as kotlinx.serialization.json.JsonObject,
        )

        val written = json.parseToJsonElement(json.encodeToString(DiarySessionRequestDto.serializer(), body))
            as kotlinx.serialization.json.JsonObject

        assertEquals(setOf("provider", "login", "credential"), written.keys)
        assertTrue("a.b.c" !in body.toString())
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
                "original_when_written": "204",
                "updated_at": "2026-09-14T18:20:00+03:00"
              },
              {
                "target": "hw:id:9001",
                "field": "text",
                "value": "Прочитать § 5 и § 6",
                "original_when_written": null,
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
        assertEquals("204", rows[0].originalWhenWritten)
        assertEquals(DiaryField.TEXT, rows[1].field)
        assertNull(rows[1].originalWhenWritten)
    }

    /**
     * The rename, which is the one place in this feature where two fields mean
     * opposite things: `original_when_written` is what the diary said when the
     * correction was made, while `DiaryEditOut.original` is what it says now.
     * A payload without it decodes — the value is genuinely optional — and so
     * does one that still spells it the old way, which is now a field this
     * client has never heard of and takes nothing from.
     */
    @Test
    fun `a stored correction decodes without the value it was written over`() {
        val decoded = json.decodeFromString<List<DiaryOverrideDto>>(
            """
            [
              {
                "target": "hw:id:9001",
                "field": "text",
                "value": "Прочитать § 5",
                "updated_at": "2026-09-14T18:21:00+03:00"
              },
              {
                "target": "hw:id:9002",
                "field": "text",
                "value": "Прочитать § 6",
                "original": "Прочитать § 7",
                "updated_at": "2026-09-14T18:22:00+03:00"
              }
            ]
            """.trimIndent(),
        )

        assertNull(decoded[0].originalWhenWritten)
        assertNull(decoded[0].toDomain()!!.originalWhenWritten)
        // The old spelling is read as nothing rather than as the new field: the
        // two say different things, and the screen draws «в дневнике: …» from
        // the other one.
        assertNull(decoded[1].originalWhenWritten)
    }

    /**
     * Two assignments in one subject due the same day, neither carrying an
     * upstream id, share a key — so the server applies no correction to either
     * and says so, sending the corrections down anyway for the reset button to
     * attach to. The flag has to reach the domain: a row that dropped it would
     * show the diary's own text with no hint that a correction for it exists
     * and is not being used.
     */
    @Test
    fun `ambiguous homework carries the flag, and an older server's carries false`() {
        val decoded = json.decodeFromString<List<DiaryHomeworkDto>>(
            """
            [
              {
                "id": null,
                "due_date": "2026-09-15",
                "subject": "Физика",
                "text": "Прочитать § 5",
                "target": "hw:2026-09-15:Физика",
                "edits": [
                  {
                    "field": "text",
                    "value": "Прочитать § 5 и § 6",
                    "original": "Прочитать § 5",
                    "changed_upstream": false
                  }
                ],
                "ambiguous": true
              },
              {
                "id": 9001,
                "due_date": "2026-09-16",
                "subject": "Химия",
                "text": "§ 3"
              }
            ]
            """.trimIndent(),
        )

        val shared = decoded[0].toDomain()!!
        assertTrue(shared.ambiguous)
        // The text is the diary's own, uncorrected, and the correction still
        // arrives: it is what «сбросить» needs to have something to reset.
        assertEquals("Прочитать § 5", shared.text)
        assertEquals("text", shared.edits.single().field)

        // A server too old to know about corrections sends no flag, and no flag
        // is the honest answer: nothing corrected, nothing ambiguous.
        assertEquals(false, decoded[1].toDomain()!!.ambiguous)
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

package com.lumenpearson.lessons.core.data.network.dto

import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Decodes a payload shaped exactly like FastAPI's, using the production [Json]
 * configuration.
 *
 * This is the test that fails when someone renames a field on one side of the
 * repo only: the `@SerialName` values here are copied from
 * `server/app/schemas.py`, not from the Kotlin property names.
 */
class BundleJsonTest {

    private val json = NetworkModule.json()

    @Test
    fun serverPayload_decodesAndMapsToDomain() {
        val payload = """
            {
              "api_version": 1,
              "school_class": {
                "id": 7,
                "name": "7 Б",
                "school": "Гимназия № 1",
                "timezone": "Europe/Moscow"
              },
              "generated_at": "2026-09-09T07:45:00+03:00",
              "server_build": "unknown-field-the-client-has-never-seen",
              "days": [
                {
                  "date": "2026-09-09",
                  "weekday": 3,
                  "kind": "shortened",
                  "lessons": [
                    {
                      "index": 1,
                      "subject": "Алгебра",
                      "starts_at": "08:30:00",
                      "ends_at": "09:15:00",
                      "room": "204",
                      "teacher": "Иванова И.И.",
                      "color": "#FF8A65",
                      "is_replaced": false,
                      "is_cancelled": false,
                      "note": null
                    },
                    {
                      "index": 2,
                      "subject": "Физика",
                      "starts_at": "09:25:00",
                      "ends_at": "10:10:00",
                      "room": null,
                      "teacher": null,
                      "color": null,
                      "is_replaced": true,
                      "is_cancelled": false,
                      "note": "Замена: Петров П.П."
                    }
                  ],
                  "events": [
                    {
                      "title": "Столовая",
                      "kind": "canteen",
                      "starts_at": "10:10:00",
                      "ends_at": "10:30:00",
                      "location": "1 этаж",
                      "covers_lesson": false
                    }
                  ],
                  "homework": [
                    {
                      "subject": "Алгебра",
                      "text": "№ 245-247",
                      "attachment_url": null
                    }
                  ],
                  "note": null
                },
                {
                  "date": "2026-09-10",
                  "weekday": 4,
                  "kind": "holiday",
                  "lessons": [],
                  "events": [],
                  "homework": [],
                  "note": "День города"
                }
              ],
              "next_school_day": {
                "date": "2026-09-11",
                "weekday": 5,
                "kind": "normal",
                "lessons": [
                  {
                    "index": 1,
                    "subject": "История",
                    "starts_at": "08:30:00",
                    "ends_at": "09:15:00",
                    "is_cancelled": false
                  }
                ],
                "events": [],
                "homework": [],
                "note": null
              }
            }
        """.trimIndent()

        val dto = json.decodeFromString(BundleDto.serializer(), payload)

        assertEquals(1, dto.apiVersion)
        assertEquals("7 Б", dto.schoolClass.name)
        assertEquals(2, dto.days.size)

        val timetable = dto.toDomain(fallbackSyncedAtEpochMillis = 0L)

        // Server enum values are lower-case; fromWire is case-insensitive.
        val today = timetable.days.first()
        assertEquals(LocalDate.of(2026, 9, 9), today.date)
        assertEquals(DayKind.SHORTENED, today.kind)
        assertEquals(LocalTime.of(8, 30), today.lessons.first().startsAt)
        assertNull(today.lessons.first().note)
        assertTrue(today.lessons[1].isReplaced)
        assertEquals(EventKind.CANTEEN, today.events.single().kind)
        assertEquals("№ 245-247", today.homework.single().text)

        val holiday = timetable.days[1]
        assertEquals(DayKind.HOLIDAY, holiday.kind)
        assertTrue(holiday.lessons.isEmpty())

        assertEquals(LocalDate.of(2026, 9, 11), timetable.nextSchoolDay?.date)
        assertTrue(timetable.syncedAtEpochMillis > 0L)
    }

    @Test
    fun sparsePayload_survivesMissingCollectionsAndFields() {
        val payload = """
            {
              "school_class": {"id": 3, "name": "3 А", "timezone": "Europe/Moscow"},
              "generated_at": "2026-09-09T07:45:00+03:00",
              "days": [{"date": "2026-09-09", "weekday": 3, "kind": "normal"}]
            }
        """.trimIndent()

        val timetable = json.decodeFromString(BundleDto.serializer(), payload)
            .toDomain(fallbackSyncedAtEpochMillis = 0L)

        assertNull(timetable.schoolClass.school)
        assertNull(timetable.nextSchoolDay)
        val day = timetable.days.single()
        assertTrue(day.lessons.isEmpty())
        assertTrue(day.events.isEmpty())
        assertTrue(day.homework.isEmpty())
        assertNull(day.note)
    }

    @Test
    fun joinResponse_decodesSnakeCaseFields() {
        val payload = """
            {"token": "abc.def", "class_id": 7, "class_name": "7 Б", "school": "Гимназия № 1"}
        """.trimIndent()

        val dto = json.decodeFromString(JoinResponseDto.serializer(), payload)

        assertEquals("abc.def", dto.token)
        assertEquals(7L, dto.classId)
        assertEquals("7 Б", dto.className)
        assertEquals("Гимназия № 1", dto.school)
    }
}

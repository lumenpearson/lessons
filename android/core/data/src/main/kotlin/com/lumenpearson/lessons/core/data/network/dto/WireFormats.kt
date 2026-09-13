package com.lumenpearson.lessons.core.data.network.dto

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Lenient parsers for the strings the server puts on the wire.
 *
 * They exist as plain functions rather than kotlinx `KSerializer`s because a
 * serializer can only fail the whole payload: here a single unreadable time
 * costs one lesson, not two weeks of cached timetable. Every function returns
 * `null` on bad input and never throws.
 */
internal object WireFormats {

    /** Fallback zone, matching the server default in `app/config.py`. */
    const val DEFAULT_TIME_ZONE_ID: String = "Europe/Moscow"

    /**
     * `2026-09-09`, and also the date half of a full ISO timestamp so a server
     * that starts sending datetimes does not blank the calendar.
     */
    fun parseDate(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty().substringBefore('T')
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * `08:30:00`, `08:30`, `08:30:00.500` and the time half of an ISO timestamp,
     * with or without a trailing offset. The offset is dropped rather than
     * applied: the whole app treats these as local wall time, exactly as the
     * server's `datetime.time` columns do.
     */
    fun parseTime(raw: String?): LocalTime? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val text = if (trimmed.contains('T')) trimmed.substringAfter('T') else trimmed
        return try {
            LocalTime.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                OffsetTime.parse(text).toLocalTime()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * `generated_at` as epoch millis. The server sends an offset-aware ISO
     * string; a naive one is read in the device zone, which is the least
     * surprising reading for a "synced N minutes ago" label.
     */
    fun parseEpochMillis(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                // device clock: the fallback for a server that sent no offset; the KDoc
                // above says why the device's reading is the least surprising one.
                LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Keeps an unknown or empty IANA id from poisoning every later time math. */
    fun sanitiseZoneId(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return DEFAULT_TIME_ZONE_ID
        return try {
            ZoneId.of(text).id
        } catch (_: Exception) {
            DEFAULT_TIME_ZONE_ID
        }
    }
}

package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.dto.AccessRequestDto
import com.lumenpearson.lessons.core.data.network.dto.AuditEntryDto
import com.lumenpearson.lessons.core.data.network.dto.AuditPageDto
import com.lumenpearson.lessons.core.data.network.dto.BellPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.network.dto.ImportConflictDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedClassDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedDeviceDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedSubjectDto
import com.lumenpearson.lessons.core.data.network.dto.RequestDecisionDto
import com.lumenpearson.lessons.core.data.network.dto.StatsDto
import com.lumenpearson.lessons.core.data.network.dto.SubjectSavedDto
import com.lumenpearson.lessons.core.data.network.dto.TimetableExportDto
import com.lumenpearson.lessons.core.data.network.dto.TimetableImportDto
import com.lumenpearson.lessons.core.data.network.dto.WireFormats
import java.time.LocalDateTime

/**
 * Wire shapes to domain types, for the management surface.
 *
 * Two rules, both borrowed from the mappers that came before:
 *
 *  * a row whose *key* cannot be read is dropped, never defaulted. A bell
 *    period without two readable times has no place on a clock, so it goes; the
 *    schedule it belonged to is still shown, one row shorter, which is the
 *    truth about what the server sent;
 *  * a field that is merely missing keeps its meaning as missing. A log line
 *    with an unreadable timestamp is still a log line, and hiding it would be
 *    hiding a change somebody made.
 *
 * Blank strings become `null` on the way through, as they do in the diary's
 * mappers: two spellings of nothing would be two renderings of it.
 */

/**
 * Class wall time, as a naive local stamp.
 *
 * [WireFormats.parseEpochMillis] is deliberately not used: it would read a
 * naive string in the *device's* zone and hand back an instant, which for an
 * admin abroad turns "изменено в 14:05" into a different hour. These stamps
 * were already converted by the server and are wall-clock facts about the
 * class, so they are parsed as such and never given a zone.
 */
private fun wallTime(raw: String?): LocalDateTime? {
    val date = WireFormats.parseDate(raw) ?: return null
    val time = WireFormats.parseTime(raw) ?: return null
    return LocalDateTime.of(date, time)
}

private fun String?.cleaned(): String? = this?.trim()?.ifBlank { null }

internal fun ManagedClassDto.toDomain(): ManagedClass = ManagedClass(
    id = id,
    name = name.trim(),
    school = school.cleaned(),
    city = city.cleaned(),
    timezone = timezone.trim(),
    // Falls back to the raw zone name: an unlabelled zone is still a zone the
    // class runs on, and an empty row would say less than "Europe/Samara".
    timezoneLabel = timezoneLabel.cleaned() ?: timezone.trim(),
    joinCode = joinCode.trim(),
    members = members,
    devices = devices,
    pendingRequests = pendingRequests,
    bellScheduleId = bellScheduleId,
    calendarReady = calendarReady,
)

internal fun ManagedSubjectDto.toDomain(): ManagedSubject = ManagedSubject(
    id = id,
    name = name.trim(),
    shortName = shortName.cleaned(),
    teacher = teacher.cleaned(),
    color = color.cleaned(),
)

internal fun SubjectSavedDto.toDomain(): SubjectSaved = SubjectSaved(
    subject = subject.toDomain(),
    moved = moved,
)

/** `null` when either time is unreadable; a period is the pair, not one of them. */
internal fun BellPeriodDto.toDomain(): BellPeriod? {
    val start = WireFormats.parseTime(startsAt) ?: return null
    val end = WireFormats.parseTime(endsAt) ?: return null
    return BellPeriod(index = index, startsAt = start, endsAt = end)
}

internal fun BellScheduleDto.toDomain(): BellSchedule = BellSchedule(
    id = id,
    name = name.trim(),
    isDefault = isDefault,
    // Sorted here rather than trusted: the server orders them, and a schedule
    // drawn out of order would be read as a mistake in the school's bells.
    periods = periods.mapNotNull { it.toDomain() }.sortedBy { it.index },
)

internal fun TimetableExportDto.toDomain(): TimetableExport = TimetableExport(
    // Not trimmed: the export is a paste format and its leading blank lines are
    // part of what makes it round-trip.
    text = text,
    lessons = lessons,
)

internal fun ImportConflictDto.toDomain(): ImportConflict = ImportConflict(
    weekday = weekday,
    existing = existing,
    incoming = incoming,
)

internal fun TimetableImportDto.toDomain(): TimetableImport = TimetableImport(
    applied = applied,
    days = days,
    lessons = lessons,
    bells = bells,
    conflicts = conflicts.map { it.toDomain() },
    rejected = rejected.mapNotNull { it.cleaned() },
)

internal fun ManagedDeviceDto.toDomain(): ManagedDevice = ManagedDevice(
    id = id,
    deviceName = deviceName.cleaned(),
    linked = linked,
    owner = owner.cleaned(),
    role = ClassRole.fromWire(role),
    revoked = revoked,
    createdAt = wallTime(createdAt),
    lastSeenAt = wallTime(lastSeenAt),
    linkedAt = wallTime(linkedAt),
)

internal fun AuditEntryDto.toDomain(): AuditEntry = AuditEntry(
    id = id,
    action = action.trim(),
    summary = summary.trim(),
    who = who.cleaned(),
    at = wallTime(at),
)

internal fun AuditPageDto.toDomain(): AuditPage = AuditPage(
    entries = entries.map { it.toDomain() },
    limit = limit,
    offset = offset,
    hasMore = hasMore,
)

internal fun StatsDto.toDomain(): ClassStats = ClassStats(
    today = WireFormats.parseDate(today),
    lessonsPerWeek = lessonsPerWeek,
    subjectsCount = subjectsCount,
    subjects = subjects.map { SubjectHours(name = it.name.trim(), hours = it.hours) },
    homeworkOpen = homeworkOpen,
    homeworkTotal = homeworkTotal,
    // A role this build does not know is dropped rather than counted under a
    // placeholder: an unnamed bar in the breakdown is worse than a shorter one.
    membersByRole = membersByRole.mapNotNull { (raw, count) ->
        ClassRole.fromWire(raw)?.let { it to count }
    }.toMap(),
    devicesActive = devicesActive,
    overridesUpcoming = overridesUpcoming,
    eventsUpcoming = eventsUpcoming,
)

internal fun AccessRequestDto.toDomain(): AccessRequest = AccessRequest(
    id = id,
    who = who.trim(),
    requestedRole = ClassRole.fromWire(requestedRole),
    message = message.cleaned(),
    createdAt = wallTime(createdAt),
)

internal fun RequestDecisionDto.toDomain(): RequestDecision = RequestDecision(
    id = id,
    approved = status.trim().equals("approved", ignoreCase = true),
    role = ClassRole.fromWire(role),
    who = who.trim(),
)

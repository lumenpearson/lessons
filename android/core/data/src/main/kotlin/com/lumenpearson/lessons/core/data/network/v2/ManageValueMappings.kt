package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.dto.WireFormats
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassStatsDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ImportTimetableResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.TimetableDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ApproveAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeclineAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ClassStats
import com.lumenpearson.lessons.core.data.repository.ImportConflict
import com.lumenpearson.lessons.core.data.repository.SubjectHours
import com.lumenpearson.lessons.core.data.repository.TimetableExport
import com.lumenpearson.lessons.core.data.repository.TimetableImport
import com.lumenpearson.lessons.core.data.repository.RequestDecision

internal fun TimetableDto.toManagedTimetable() = TimetableExport(text, lessons)

internal fun ImportTimetableResponseDto.toManagedImport() = TimetableImport(
    applied, days, lessons, bells, conflicts.map { ImportConflict(it.weekday, it.existing, it.incoming) },
    rejected.mapNotNull { it.cleanManaged() },
)

internal fun ClassStatsDto.toManagedStats() = ClassStats(
    WireFormats.parseDate(today), lessonsPerWeek, subjectsCount,
    subjects.map { SubjectHours(it.name.trim(), it.hours) }, homeworkOpen, homeworkTotal,
    membersByRole.mapNotNull { row -> managedRole(row.role)?.let { it to row.count } }.toMap(),
    devicesActive, substitutionsUpcoming, eventsUpcoming,
)

internal fun managedRole(value: String): ClassRole? = ClassRole.fromWire(value.trim().removePrefix("ROLE_"))

internal fun ApproveAccessRequestResponseDto.toManagedDecision() =
    RequestDecision(requestId.toLong(), true, managedRole(role), who.trim())

internal fun DeclineAccessRequestResponseDto.toManagedDecision() =
    RequestDecision(requestId.toLong(), false, null, who.trim())

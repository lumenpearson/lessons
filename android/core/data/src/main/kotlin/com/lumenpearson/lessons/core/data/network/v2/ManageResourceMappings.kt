package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.dto.WireFormats
import com.lumenpearson.lessons.core.data.network.v2.dto.BellPeriodDto
import com.lumenpearson.lessons.core.data.network.v2.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SubjectDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UpdateBellScheduleResponseDto
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.BellsWritten
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import java.time.format.DateTimeFormatter

internal fun SchoolClassDto.toManagedClass() = ManagedClass(
    id.toLong(), name.trim(), school.cleanManaged(), city.cleanManaged(), timezone.trim(),
    timezoneLabel.cleanManaged() ?: timezone.trim(), joinCode.trim(),
    ClassJoinMode.fromWire(joinMode.removePrefix("JOIN_MODE_")), members, devices, pendingRequests,
    bellScheduleId?.toLong(), calendarReady,
)

internal fun SubjectDto.toManagedSubject() = ManagedSubject(
    id.toLong(), name.trim(), shortName.cleanManaged(), teacher.cleanManaged(), color.cleanManaged(),
)

private fun BellPeriodDto.toManagedPeriod(): BellPeriod? {
    val start = WireFormats.parseTime(startsAt)
    val end = WireFormats.parseTime(endsAt)
    return if (start == null || end == null) null else BellPeriod(index, start, end)
}

internal fun BellScheduleDto.toManagedBells() = BellSchedule(
    id.toLong(), name.trim(), isDefault, periods.mapNotNull { it.toManagedPeriod() }.sortedBy { it.index },
)

internal fun BellPeriod.resource() = BellPeriodDto(
    index, startsAt.format(ManagementMinute), endsAt.format(ManagementMinute),
)

private val ManagementMinute = DateTimeFormatter.ofPattern("HH:mm")

internal fun String?.cleanManaged(): String? = this?.trim()?.ifBlank { null }

internal fun SchoolClassDto?.requiredManagedClass(status: Int = 200): ManagedClass =
    this?.toManagedClass() ?: throw malformedV2Payload("The response has no class", status)

internal fun SubjectDto?.requiredManagedSubject(status: Int = 200): ManagedSubject =
    this?.toManagedSubject() ?: throw malformedV2Payload("The response has no subject", status)

internal fun BellScheduleDto?.requiredManagedBells(status: Int = 200): BellSchedule =
    this?.toManagedBells() ?: throw malformedV2Payload("The response has no bell schedule", status)

internal fun UpdateBellScheduleResponseDto.toManagedBellWrite(status: Int = 200) = BellsWritten(
    schedule.requiredManagedBells(status), silencedLessons.coerceAtLeast(0),
)

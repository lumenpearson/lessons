package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.AccessRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.AuditEntryDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassDeviceDto
import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.AuditEntry
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun ClassDeviceDto.toManagedDevice(zone: ZoneId) = ManagedDevice(
    id.toLong(), deviceName.cleanManaged(), linked, owner.cleanManaged(), managedRole(role), revoked,
    classWallTime(createdAt, zone), classWallTime(lastSeenAt, zone), classWallTime(linkedAt, zone),
)

internal fun AccessRequestDto.toManagedRequest(zone: ZoneId) = AccessRequest(
    id.toLong(), who.trim(), managedRole(requestedRole), message.cleanManaged(), classWallTime(createdAt, zone),
)

internal fun AuditEntryDto.toManagedAudit(zone: ZoneId) = AuditEntry(
    id.toLong(), action.trim(), summary.trim(), who.cleanManaged(), classWallTime(at, zone),
)

private fun classWallTime(value: String?, zone: ZoneId): LocalDateTime? = try {
    value?.let { LocalDateTime.ofInstant(OffsetDateTime.parse(it).toInstant(), zone) }
} catch (_: DateTimeException) {
    null
}

internal fun managementZone(value: String): ZoneId = try {
    ZoneId.of(value.trim())
} catch (_: DateTimeException) {
    // Guessing a zone would turn an invalid reply into a convincing but incorrect audit time.
    throw malformedV2Payload("The class response has no valid time zone")
}

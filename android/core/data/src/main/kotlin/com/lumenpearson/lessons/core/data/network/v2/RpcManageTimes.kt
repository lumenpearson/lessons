package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.Timestamp
import com.lumenpearson.lessons.contract.v2.AccessRequest
import com.lumenpearson.lessons.contract.v2.AuditEntry
import com.lumenpearson.lessons.contract.v2.ClassDevice
import com.lumenpearson.lessons.core.data.network.v2.dto.AccessRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.AuditEntryDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassDeviceDto
import com.lumenpearson.lessons.core.data.network.v2.dto.RoleWireSerializer
import java.time.Instant

internal fun ClassDevice.canonicalManagedDevice() = ClassDeviceDto(
    id, deviceName.takeIf { hasDeviceName() }, linked, owner.takeIf { hasOwner() },
    RoleWireSerializer.wireName(roleValue), revoked,
    createdAt.takeIf { hasCreatedAt() }?.canonicalManagedInstant(),
    lastSeenAt.takeIf { hasLastSeenAt() }?.canonicalManagedInstant(),
    linkedAt.takeIf { hasLinkedAt() }?.canonicalManagedInstant(),
    clientVersion.takeIf { hasClientVersion() },
)

internal fun AccessRequest.canonicalManagedRequest() = AccessRequestDto(
    id, who, RoleWireSerializer.wireName(requestedRoleValue), message.takeIf { hasMessage() },
    createdAt.takeIf { hasCreatedAt() }?.canonicalManagedInstant(),
)

internal fun AuditEntry.canonicalManagedAudit() = AuditEntryDto(
    id, action, summary, who.takeIf { hasWho() }, at.takeIf { hasAt() }?.canonicalManagedInstant(),
)

/** Timestamp's protocol range prevents normalizing malformed nanoseconds into another instant. */
private fun Timestamp.canonicalManagedInstant(): String? =
    if (seconds !in MinTimestampSeconds..MaxTimestampSeconds || nanos !in 0..MaxTimestampNanos) null
    else Instant.ofEpochSecond(seconds, nanos.toLong()).toString()

private const val MinTimestampSeconds = -62_135_596_800L
private const val MaxTimestampSeconds = 253_402_300_799L
private const val MaxTimestampNanos = 999_999_999

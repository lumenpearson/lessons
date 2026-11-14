package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DeviceLink

internal fun deviceLink(name: String?, linked: Boolean, role: String, canEdit: Boolean): DeviceLink =
    DeviceLink(name?.trim()?.ifBlank { null }, linked,
        ClassRole.fromWire(role.trim().removePrefix("ROLE_")), canEdit, null, null)

internal fun DeviceLink.withLinkCode(code: String?, deepLink: String?): DeviceLink = copy(
    linkCode = code?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw malformedV2Payload("The unlinked phone received no link code"),
    botDeepLink = deepLink?.trim()?.ifBlank { null },
)

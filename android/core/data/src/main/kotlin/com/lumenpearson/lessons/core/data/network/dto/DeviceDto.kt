package com.lumenpearson.lessons.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `GET /api/v1/me`: what the server knows about this device.
 *
 * Mirrors the `/me` endpoint in `server/app/api/public.py`. Nothing here
 * identifies the person — the server deliberately never returns a Telegram
 * id — so the whole object is safe to keep in memory and to log.
 *
 * @property linkCode present only while the device is not linked. It is what
 *   the user types into the bot (or carries in [botDeepLink]) to tie this
 *   phone to their Telegram account and, through it, to their role in the
 *   class.
 * @property botDeepLink `https://t.me/<bot>?start=link_<code>`, or `null` when
 *   the server was not told its bot's username; the code still works typed.
 */
@Serializable
internal data class DeviceMeDto(
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("linked") val linked: Boolean = false,
    @SerialName("role") val role: String? = null,
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("link_code") val linkCode: String? = null,
    @SerialName("bot_deep_link") val botDeepLink: String? = null,
)

/** Response of `POST /api/v1/me/unlink`. */
@Serializable
internal data class UnlinkResponseDto(
    @SerialName("linked") val linked: Boolean = false,
)

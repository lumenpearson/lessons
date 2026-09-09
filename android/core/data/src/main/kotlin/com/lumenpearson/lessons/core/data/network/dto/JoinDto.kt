package com.lumenpearson.lessons.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/v1/join`.
 *
 * Mirrors `JoinRequest` in `server/app/schemas.py`; the field names are the
 * contract, so every property carries an explicit [SerialName] even where the
 * Kotlin name happens to match.
 */
@Serializable
internal data class JoinRequestDto(
    @SerialName("code") val code: String,
    @SerialName("device_name") val deviceName: String? = null,
)

/**
 * Response of `POST /api/v1/join`.
 *
 * [token] is a long-lived read-only bearer token: there is no refresh flow, so
 * losing it means going through the join screen again.
 */
@Serializable
internal data class JoinResponseDto(
    @SerialName("token") val token: String,
    @SerialName("class_id") val classId: Long,
    @SerialName("class_name") val className: String,
    @SerialName("school") val school: String? = null,
)

/**
 * Response of `GET /api/v1/health`.
 *
 * Used by the join screen to tell "wrong address" apart from "wrong code"
 * before the user starts doubting the code the teacher gave them.
 */
@Serializable
internal data class HealthDto(
    @SerialName("status") val status: String = "unknown",
    @SerialName("api_version") val apiVersion: Int = 0,
)

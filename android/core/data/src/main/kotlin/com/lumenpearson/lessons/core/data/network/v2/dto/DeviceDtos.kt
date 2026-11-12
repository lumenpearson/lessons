package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateDeviceRequestDto(
    val code: String = "",
    val deviceName: String? = null,
)

@Serializable
internal data class CreateDeviceResponseDto(
    val token: String = "",
    val classId: Int = 0,
    val className: String = "",
    val school: String? = null,
    val timezone: String = "",
    val diary: DiaryBindingDto? = null,
)

@Serializable
internal data class DiaryBindingDto(
    val provider: String = "",
    val region: String? = null,
    @Serializable(with = Int64StringSerializer::class)
    val schoolId: Long? = null,
    val schoolName: String? = null,
)

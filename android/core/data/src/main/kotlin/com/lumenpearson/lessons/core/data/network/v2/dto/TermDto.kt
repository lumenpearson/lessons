package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class TermDto(
    val index: Int = 0,
    @Serializable(with = TermKindWireSerializer::class)
    val kind: String = "",
    val startsOn: String = "",
    val endsOn: String = "",
)

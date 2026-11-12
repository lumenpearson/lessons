package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object Int64StringSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("Int64String", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Long {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON is required for int64")
        return input.decodeJsonElement().jsonPrimitive.longOrNull
            ?: throw SerializationException("An exact signed int64 is required")
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
}

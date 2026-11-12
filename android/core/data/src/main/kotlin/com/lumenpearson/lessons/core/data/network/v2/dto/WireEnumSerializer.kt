package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal open class WireEnumSerializer(private val names: Map<Int, String>) : KSerializer<String> {
    fun wireName(number: Int): String = names[number] ?: number.toString()
    override val descriptor = PrimitiveSerialDescriptor("WireEnum", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON is required for a wire enum")
        val value = input.decodeJsonElement().jsonPrimitive
        if (value.isString) return value.content
        val number = value.intOrNull ?: throw SerializationException("An enum name or integer is required")
        return wireName(number)
    }

    override fun serialize(encoder: Encoder, value: String) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("JSON is required for a wire enum")
        output.encodeJsonElement(value.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value))
    }
}

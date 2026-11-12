package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class V2GoldenTest {
    private val json = Json { explicitNulls = false }

    private fun read(name: String) = requireNotNull(javaClass.getResource("/v2/$name")) {
        "Missing shared v2 golden $name"
    }.readText()

    private fun rows() = json.parseToJsonElement(read("index.json")).jsonObject.getValue("methods").jsonArray

    @Test
    fun `every unary request and response has a strict DTO and canonical encoding`() {
        val methods = rows().filter { it.jsonObject.getValue("rest").jsonPrimitive.content == "true" }
        assertEquals(75, methods.size)
        assertEquals(
            methods.map { it.jsonObject.getValue("method").jsonPrimitive.content }.toSet(),
            CanonicalMethods.unary.keys,
        )
        for (element in methods) {
            val row = element.jsonObject
            val name = row.getValue("method").jsonPrimitive.content
            val dto = requireNotNull(CanonicalMethods.unary[name]) { name }
            for (sample in row.getValue("samples").jsonArray) {
                val request = json.parseToJsonElement(
                    read(sample.jsonObject.getValue("request").jsonPrimitive.content),
                )
                val response = json.parseToJsonElement(
                    read(sample.jsonObject.getValue("response").jsonPrimitive.content),
                )
                assertEquals("$name request", request, dto.request.canonical(json, request))
                assertEquals("$name response", response, dto.response.canonical(json, response))
                val extra = JsonObject(response.jsonObject + ("unrelatedFutureField" to JsonPrimitive("refuse")))
                assertThrows("$name must reject an unknown key", SerializationException::class.java) {
                    dto.response.canonical(json, extra)
                }
            }
        }
    }

    @Test
    fun `credential inventory includes the explicitly non REST stream`() {
        val expected = json.parseToJsonElement(read("credentials.json")).jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        assertEquals(76, expected.size)
        assertEquals(expected, CanonicalMethods.credentials)
        assertEquals(setOf("lessons.v2.WatchService/WatchClass"), CanonicalMethods.streaming)
        assertEquals("none", expected["lessons.v2.DeviceService/CreateDevice"])
        assertEquals("device", expected["lessons.v2.ScheduleService/GetScheduleWindow"])
    }

    @Test
    fun `int64 retains the first value beyond double precision and encodes a string`() {
        val value = json.decodeFromString(Int64StringSerializer, "\"9007199254740993\"")
        assertEquals(9_007_199_254_740_993L, value)
        assertEquals("\"9007199254740993\"", json.encodeToString(Int64StringSerializer, value))
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(Int64StringSerializer, "1.5")
        }
    }

    @Test
    fun `unknown enum numbers and names do not drop neighboring known elements`() {
        val serializer = ListSerializer(DiaryFeatureWireSerializer)
        val decoded = json.decodeFromString(serializer, "[\"DIARY_FEATURE_SCHEDULE\",99,\"A_FUTURE_ENUM_NAME\",2]")

        assertEquals(listOf("DIARY_FEATURE_SCHEDULE", "99", "A_FUTURE_ENUM_NAME", "DIARY_FEATURE_HOMEWORK"), decoded)
        assertEquals(
            "[\"DIARY_FEATURE_SCHEDULE\",99,\"A_FUTURE_ENUM_NAME\",\"DIARY_FEATURE_HOMEWORK\"]",
            json.encodeToString(serializer, decoded),
        )
        assertThrows(SerializationException::class.java) { json.decodeFromString(serializer, "[true]") }
    }

    @Test
    fun `known nested window fields remain declared even when domain mapping does not read them`() {
        val original = json.parseToJsonElement(read("ScheduleService.GetScheduleWindow.response.json")).jsonObject
        val dto = json.decodeFromJsonElement(GetScheduleWindowResponseDto.serializer(), original)

        assertEquals("sample", dto.window?.schoolClass?.city)
        assertNotNull(dto.window?.device)
        assertEquals("2026-09-21T14:13:20.005Z", dto.window?.generatedAt)
        val row = rows().single {
            it.jsonObject.getValue("method").jsonPrimitive.content.endsWith("/GetScheduleWindow")
        }
        val unread = row.jsonObject.getValue("unread").jsonObject
        assertEquals(setOf("window.schoolClass.city", "window.device"), unread.keys)
        val window = original.getValue("window").jsonObject
        val school = window.getValue("schoolClass").jsonObject
        val changedSchool = JsonObject(school + ("unrelatedFutureField" to JsonPrimitive("refuse")))
        val changedWindow = JsonObject(window + ("schoolClass" to changedSchool))
        assertThrows(SerializationException::class.java) {
            json.decodeFromJsonElement(
                GetScheduleWindowResponseDto.serializer(), JsonObject(original + ("window" to changedWindow)),
            )
        }
    }
}

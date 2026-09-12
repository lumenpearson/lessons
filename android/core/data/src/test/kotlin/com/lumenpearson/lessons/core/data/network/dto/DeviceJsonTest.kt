package com.lumenpearson.lessons.core.data.network.dto

import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.ClassRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `/me` payload, as the server sends it and as it may grow.
 *
 * Every field is optional on purpose: a server that predates the link feature
 * answers this endpoint with 404, and one that postdates the app may add
 * fields; neither must break decoding.
 */
class DeviceJsonTest {

    private val json = NetworkModule.json()

    @Test
    fun unlinked_payload_carries_code_and_deep_link() {
        val dto = json.decodeFromString(
            DeviceMeDto.serializer(),
            """
            {"device_name": "Pixel 8", "linked": false, "role": null, "can_edit": false,
             "link_code": "A7K2QX", "bot_deep_link": "https://t.me/less0nz_bot?start=link_A7K2QX"}
            """.trimIndent(),
        )
        assertFalse(dto.linked)
        assertEquals("A7K2QX", dto.linkCode)
        assertEquals("https://t.me/less0nz_bot?start=link_A7K2QX", dto.botDeepLink)
        assertNull(ClassRole.fromWire(dto.role))
    }

    @Test
    fun linked_payload_maps_role_case_insensitively() {
        val dto = json.decodeFromString(
            DeviceMeDto.serializer(),
            """{"linked": true, "role": "editor", "can_edit": true, "link_code": null}""",
        )
        assertTrue(dto.linked)
        assertTrue(dto.canEdit)
        assertEquals(ClassRole.EDITOR, ClassRole.fromWire(dto.role))
        assertNull(dto.linkCode)
    }

    @Test
    fun unknown_role_and_unknown_fields_do_not_break_decoding() {
        val dto = json.decodeFromString(
            DeviceMeDto.serializer(),
            """{"linked": true, "role": "principal", "future_field": 1}""",
        )
        assertTrue(dto.linked)
        assertNull(ClassRole.fromWire(dto.role))
        assertFalse(dto.canEdit)
    }

    @Test
    fun empty_object_decodes_to_the_unlinked_default() {
        val dto = json.decodeFromString(DeviceMeDto.serializer(), "{}")
        assertFalse(dto.linked)
        assertNull(dto.linkCode)
        assertNull(dto.botDeepLink)
    }
}

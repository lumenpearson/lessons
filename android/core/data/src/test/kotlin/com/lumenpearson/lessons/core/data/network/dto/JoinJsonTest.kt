package com.lumenpearson.lessons.core.data.network.dto

import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The join's answer and the class's diary binding it carries (K25): the only
 * place a phone learns which diary its class reads, so a family that joined by
 * code signs in without searching for their school.
 */
class JoinJsonTest {

    private val json = NetworkModule.json()

    @Test
    fun `a join with a binding carries it`() {
        val dto = json.decodeFromString(
            JoinResponseDto.serializer(),
            """
            {"token": "t", "class_id": 7, "class_name": "7А", "school": null, "timezone": "Europe/Samara",
             "diary": {"provider": "netschool", "region": "samara", "school_id": 1234, "school_name": "Школа № 5"}}
            """.trimIndent(),
        )

        assertEquals(
            DiaryBinding(DiaryProviderKey.NETSCHOOL, "samara", 1234, "Школа № 5"),
            dto.diary?.toDomain(),
        )
    }

    @Test
    fun `a server from before bindings, and a class with none, read as unbound`() {
        val older = json.decodeFromString(
            JoinResponseDto.serializer(),
            """{"token": "t", "class_id": 7, "class_name": "7А"}""",
        )
        val none = json.decodeFromString(
            JoinResponseDto.serializer(),
            """{"token": "t", "class_id": 7, "class_name": "7А", "timezone": "Europe/Moscow", "diary": null}""",
        )

        assertNull(older.diary)
        assertNull(none.diary)
    }

    /** A provider a later server adds is no binding, never Petersburg by guess. */
    @Test
    fun `an unknown provider is no binding`() {
        assertNull(DiaryBindingDto(provider = "eljur", region = "x").toDomain())
        assertNull(DiaryBindingDto().toDomain())
    }

    @Test
    fun `a binding becomes a sign-in target only when it has what the diary needs`() {
        val petersburg = DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null)
        assertEquals(DiaryTarget.petersburg("parent"), petersburg.targetFor("parent"))

        val samara = DiaryBinding(DiaryProviderKey.NETSCHOOL, "samara", 1234, "Школа № 5")
        val target = samara.targetFor("ivanova")
        assertEquals("samara", target?.region)
        assertEquals(1234L, target?.schoolId)

        assertNull(samara.copy(schoolId = null).targetFor("ivanova"))
        assertNull(samara.copy(region = " ").targetFor("ivanova"))
    }
}

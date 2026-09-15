package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.ManageApi
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.dto.ClassPatchDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedClassDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The join mode, from the wire to the card and back.
 *
 * Three things are worth pinning, and all three are about a build meeting a
 * server it was not compiled against. Two of them are the same decision in two
 * spellings — a mode this app has never heard of, and no mode at all, both read
 * as «открытый», which is what every class was before the feature existed and
 * the only reading that does not put a padlock over a working class code. The
 * third is the opposite direction: the toggle on the card must send the one
 * field it is a toggle for, because the card it was tapped on may be a minute
 * old and the bot may have renamed the class since.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageJoinModeTest {

    private val json = NetworkModule.json()

    @Test
    fun `an invite-only class arrives as one`() {
        val decoded = json.decodeFromString<ManagedClassDto>(
            """{ "id": 1, "name": "9А", "join_code": "DEMO24", "join_mode": "invite" }""",
        )

        assertEquals(ClassJoinMode.INVITE, decoded.toDomain().joinMode)
    }

    /**
     * A mode from a newer server. The class is still a class and the card still
     * draws; the one thing it must not do is refuse to decode the whole card
     * over a word in one field.
     */
    @Test
    fun `an unknown join mode reads as open`() {
        val decoded = json.decodeFromString<ManagedClassDto>(
            """{ "id": 1, "name": "9А", "join_code": "DEMO24", "join_mode": "moderated" }""",
        )

        assertEquals(ClassJoinMode.OPEN, decoded.toDomain().joinMode)
    }

    /** A server from before the feature sends no such key at all. */
    @Test
    fun `a missing join mode reads as open`() {
        val decoded = json.decodeFromString<ManagedClassDto>(
            """{ "id": 1, "name": "9А", "join_code": "DEMO24" }""",
        )

        assertEquals(ClassJoinMode.OPEN, decoded.toDomain().joinMode)
    }

    @Test
    fun `switching the mode patches that field and nothing else`() = runTest {
        val api = RecordingManageApi()

        val result = ManageRepositoryImpl(
            api = api,
            ioDispatcher = UnconfinedTestDispatcher(),
        ).setJoinMode(ClassJoinMode.INVITE)

        assertEquals(ClassJoinMode.INVITE, result.getOrNull()?.joinMode)
        // Serialized rather than compared field by field: what matters is the
        // body on the wire, and `explicitNulls = false` is what keeps the four
        // fields this toggle never touches out of it.
        assertEquals("""{"join_mode":"invite"}""", json.encodeToString(api.patches.single()))
    }

    @Test
    fun `going back to the class code patches that field too`() = runTest {
        val api = RecordingManageApi()

        ManageRepositoryImpl(api = api, ioDispatcher = UnconfinedTestDispatcher())
            .setJoinMode(ClassJoinMode.OPEN)

        assertEquals("""{"join_mode":"open"}""", json.encodeToString(api.patches.single()))
    }

    /** Keeps every patch it is sent, and echoes the mode back as the server does. */
    private class RecordingManageApi : ManageApi by UnusedManageApi() {

        val patches: MutableList<ClassPatchDto> = mutableListOf()

        override suspend fun updateClass(body: ClassPatchDto): ManagedClassDto {
            patches += body
            return ManagedClassDto(
                id = 1,
                name = "9А",
                joinCode = "DEMO24",
                joinMode = body.joinMode ?: "open",
            )
        }
    }
}

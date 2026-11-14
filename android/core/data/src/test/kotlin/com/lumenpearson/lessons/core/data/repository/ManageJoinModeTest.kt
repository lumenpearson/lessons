package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.v2.V2BindingFixture
import com.lumenpearson.lessons.core.data.network.v2.toManagedClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import java.util.concurrent.TimeUnit
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
        val decoded = json.decodeFromString<SchoolClassDto>(
            """{ "id": 1, "name": "9А", "joinCode": "DEMO24", "joinMode": "JOIN_MODE_INVITE" }""",
        )

        assertEquals(ClassJoinMode.INVITE, decoded.toManagedClass().joinMode)
    }

    /**
     * A mode from a newer server. The class is still a class and the card still
     * draws; the one thing it must not do is refuse to decode the whole card
     * over a word in one field.
     */
    @Test
    fun `an unknown join mode reads as open`() {
        val decoded = json.decodeFromString<SchoolClassDto>(
            """{ "id": 1, "name": "9А", "joinCode": "DEMO24", "joinMode": "moderated" }""",
        )

        assertEquals(ClassJoinMode.OPEN, decoded.toManagedClass().joinMode)
    }

    /** A server from before the feature sends no such key at all. */
    @Test
    fun `a missing join mode reads as open`() {
        val decoded = json.decodeFromString<SchoolClassDto>(
            """{ "id": 1, "name": "9А", "joinCode": "DEMO24" }""",
        )

        assertEquals(ClassJoinMode.OPEN, decoded.toManagedClass().joinMode)
    }

    @Test
    fun `switching the mode patches that field and nothing else`() = runTest {
        V2BindingFixture("rest").use { fixture ->
            fixture.success("""{"schoolClass":{"id":1,"name":"9A","joinMode":"JOIN_MODE_INVITE"}}""",
                com.lumenpearson.lessons.contract.v2.UpdateClassResponse.newBuilder().setSchoolClass(
                    com.lumenpearson.lessons.contract.v2.SchoolClass.newBuilder().setId(1).setName("9A")
                        .setJoinMode(com.lumenpearson.lessons.contract.v2.JoinMode.JOIN_MODE_INVITE)).build())
            val repository = ManageRepositoryImpl(fixture.manage, UnconfinedTestDispatcher())
            val result = repository.setJoinMode(ClassJoinMode.INVITE)
            assertEquals(ClassJoinMode.INVITE, result.getOrNull()?.joinMode)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("joinMode", sent.url.queryParameter("updateMask"))
            assertEquals("""{"joinMode":"JOIN_MODE_INVITE"}""", checkNotNull(sent.body).utf8())
        }
    }

    @Test
    fun `going back to the class code patches that field too`() = runTest {
        V2BindingFixture("rest").use { fixture ->
            fixture.success("""{"schoolClass":{"id":1,"name":"9A","joinMode":"JOIN_MODE_OPEN"}}""",
                com.lumenpearson.lessons.contract.v2.UpdateClassResponse.newBuilder().setSchoolClass(
                    com.lumenpearson.lessons.contract.v2.SchoolClass.newBuilder().setId(1).setName("9A")
                        .setJoinMode(com.lumenpearson.lessons.contract.v2.JoinMode.JOIN_MODE_OPEN)).build())
            ManageRepositoryImpl(fixture.manage, UnconfinedTestDispatcher()).setJoinMode(ClassJoinMode.OPEN)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("joinMode", sent.url.queryParameter("updateMask"))
            assertEquals("""{"joinMode":"JOIN_MODE_OPEN"}""", checkNotNull(sent.body).utf8())
        }
    }
}

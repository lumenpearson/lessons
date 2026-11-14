package com.lumenpearson.lessons.core.data.repository

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.network.v2.ManageRemote
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.RemoteError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManageMembershipTest {
    @Test
    fun `a late management token refusal removes A and leaves newly selected B and its cache intact`() = runTest {
        val fixture = V2InFlightMembershipTest.Fixture()
        fixture.seed()
        val beforeB = fixture.dao.snapshot(12)
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val repository = repository(fixture, invalid(), started, answer)
        val pending = async { repository.classCard() }
        started.await()
        fixture.sessions.select(12)
        answer.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertEquals(listOf(12L), fixture.preferences.currentSessions().map { it.classId })
        assertEquals(12L, fixture.sessions.current()?.classId)
        assertNull(fixture.dao.snapshot(7))
        assertEquals(beforeB, fixture.dao.snapshot(12))
        fixture.assertNoTags(7)
        assertEquals("v2:12", fixture.preferences.v2BundleTag("12|2026"))
    }

    @Test
    fun `a late management refusal cannot remove a replacement token for the same class`() =
        assertPreserved {
            it.sessions.join("SYNTHETIC", null).getOrThrow()
            assertEquals("synthetic-replacement", it.sessions.current()?.token)
        }

    @Test
    fun `a late management refusal from the previous server cannot remove current memberships`() =
        assertPreserved { fixture ->
            fixture.preferences.updateSettings { it.copy(baseUrl = "https://other.invalid/") }
        }

    @Test
    fun `a bare gateway 401 cannot remove a membership`() = assertPreserved(
        failure = RemoteError(Code.UNAUTHENTICATED, httpStatus = 401),
    ) {}

    @Test
    fun `a structured role refusal keeps the phone and its cache in the class`() = assertPreserved(
        failure = RemoteError(Code.PERMISSION_DENIED, "ROLE_REQUIRED",
            mapOf("role" to "ROLE_ADMIN"), httpStatus = 403),
    ) {}

    @Test
    fun `a token refusal without an observed send cannot revoke a guessed membership`() =
        assertPreserved(observed = false) {}

    private fun assertPreserved(
        failure: RemoteError = invalid(),
        observed: Boolean = true,
        change: suspend (V2InFlightMembershipTest.Fixture) -> Unit,
    ) = runTest {
        val fixture = V2InFlightMembershipTest.Fixture()
        fixture.seed()
        val beforeB = fixture.dao.snapshot(12)
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val pending = async { repository(fixture, failure, started, answer, observed).classCard() }
        started.await()
        try {
            val unlocked = fixture.preferences.membershipChanges.tryLock()
            if (unlocked) fixture.preferences.membershipChanges.unlock()
            assertTrue("The management network call must not hold the persistence gate", unlocked)
            change(fixture)
        } finally {
            answer.complete(Unit)
        }
        assertTrue(pending.await().isFailure)
        assertEquals(setOf(7L, 12L), fixture.preferences.currentSessions().map { it.classId }.toSet())
        assertEquals(beforeB, fixture.dao.snapshot(12))
        assertEquals("v2:12", fixture.preferences.v2BundleTag("12|2026"))
    }

    // The actual send is explicit so the missing-observation control cannot revoke a guessed owner.
    private fun repository(
        fixture: V2InFlightMembershipTest.Fixture,
        failure: RemoteError,
        started: CompletableDeferred<Unit>,
        answer: CompletableDeferred<Unit>,
        observed: Boolean = true,
    ) = ManageRepositoryImpl(
        api = object : ManageRemote by UnusedManageRemote {
            override suspend fun classCard(observation: CallObservation): ManagedClass {
                if (observed) observation.recordRequest(fixture.preferences.currentClassRequest().second)
                started.complete(Unit)
                answer.await()
                throw failure
            }
        },
        ioDispatcher = UnconfinedTestDispatcher(),
        membership = ScheduleMembership(fixture.preferences) { fixture.sessions.rejectRequest(it) },
    )

    private companion object {
        fun invalid() = RemoteError(Code.UNAUTHENTICATED, "DEVICE_TOKEN_INVALID", httpStatus = 401)
    }
}

package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DiaryTokenOwnershipTest(private val transport: String) {
    @Test(timeout = 20_000)
    fun `a late diary refusal cannot clear a replacement bearer written by another preferences instance`() = late {
        it.otherPreferences().writeDiarySession(it.session.copy(token = "synthetic-diary-B"))
        "synthetic-diary-B"
    }

    @Test(timeout = 20_000)
    fun `a late diary refusal cannot clear the same bearer under another server`() = late {
        it.preferences.updateSettings { settings -> settings.copy(baseUrl = "https://other.invalid/") }
        "synthetic-diary-A"
    }

    @Test(timeout = 20_000)
    fun `checking and clearing the diary bearer is one atomic store update`() = runBlocking {
        DiaryTokenFixture(transport).use { fixture ->
            fixture.seed()
            fixture.refuse()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.store.beforeWrite = { entered.complete(Unit); release.await() }
            val pending = async { fixture.repository().students() }
            try {
                entered.await()
                fixture.otherPreferences().writeDiarySession(fixture.session.copy(token = "synthetic-diary-B"))
            } finally {
                release.complete(Unit)
            }
            assertSame(DiaryFailure.SignInRequired, pending.await().exceptionOrNull())
            assertEquals("synthetic-diary-B", fixture.preferences.currentDiarySession()?.token)
            fixture.assertRequest()
        }
    }

    @Test(timeout = 20_000)
    fun `a current invalid diary token clears only that bearer and preserves its target pupil and class`() =
        runBlocking {
        DiaryTokenFixture(transport).use { fixture ->
            fixture.seed()
            fixture.refuse()
            assertSame(DiaryFailure.SignInRequired, fixture.repository().students().exceptionOrNull())
            assertNull(fixture.preferences.currentDiarySession())
            assertEquals(fixture.session.target, fixture.preferences.currentDiaryTarget())
            assertEquals(17L, fixture.preferences.selectedStudentId.first())
            assertEquals("synthetic-class", fixture.preferences.currentSession()?.token)
            fixture.assertRequest()
        }
    }

    @Test(timeout = 20_000)
    fun `an unknown authentication reason retains the approved canonical code fallback`() = runBlocking {
        DiaryTokenFixture(transport).use { fixture ->
            fixture.seed()
            fixture.refuse("FUTURE_DIARY_AUTH")
            assertSame(DiaryFailure.SignInRequired, fixture.repository().students().exceptionOrNull())
            assertNull(fixture.preferences.currentDiarySession())
            fixture.assertRequest()
        }
    }

    @Test(timeout = 20_000)
    fun `a non authentication refusal retains the diary token`() = runBlocking {
        DiaryTokenFixture(transport).use { fixture ->
            fixture.seed()
            fixture.refuse("FUTURE_DIARY_STATE", authentication = false)
            assertSame(DiaryFailure.Unavailable, fixture.repository().students().exceptionOrNull())
            assertEquals("synthetic-diary-A", fixture.preferences.currentDiarySession()?.token)
            fixture.assertRequest()
        }
    }

    @Test(timeout = 20_000)
    fun `a refusal without an observed send cannot clear a diary bearer`() = runBlocking {
        DiaryTokenFixture(transport).use { fixture ->
            fixture.seed()
            val unobserved = object : DiaryRemote by fixture.binding.diary {
                override suspend fun students(observation: CallObservation): List<DiaryStudent> =
                    throw RemoteError(Code.UNAUTHENTICATED, "DIARY_TOKEN_INVALID", httpStatus = 401)
            }
            assertSame(DiaryFailure.SignInRequired, fixture.repository(unobserved).students().exceptionOrNull())
            assertEquals("synthetic-diary-A", fixture.preferences.currentDiarySession()?.token)
            assertEquals(0, fixture.binding.server.requestCount)
        }
    }

    @Test(timeout = 20_000)
    fun `cancelling the conditional token write is never converted to a sign in failure`() = runBlocking {
        DiaryTokenFixture(transport).use { fixture ->
            fixture.seed()
            fixture.refuse()
            val cancellation = CancellationException("synthetic closed screen")
            fixture.store.beforeWrite = { throw cancellation }
            val failure = runCatching { fixture.repository().students() }.exceptionOrNull()
            // Stack recovery can copy the throwable while retaining the original cancellation as its cause.
            assertTrue(failure is CancellationException)
            assertTrue(generateSequence(failure) { it.cause }.take(8).any { it === cancellation })
            assertEquals("synthetic-diary-A", fixture.preferences.currentDiarySession()?.token)
            fixture.assertRequest()
        }
    }

    private fun late(change: suspend (DiaryTokenFixture) -> String) = runBlocking {
        DiaryTokenFixture(transport).use { fixture ->
            fixture.seed()
            fixture.refuse()
            val sent = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            fixture.binding.afterResponse = {
                sent.complete(Unit)
                check(release.await(3, TimeUnit.SECONDS)) { "The test did not release its response" }
            }
            val pending = async { fixture.repository().students() }
            sent.await()
            val expected = try { change(fixture) } finally { release.countDown() }
            assertSame(DiaryFailure.SignInRequired, pending.await().exceptionOrNull())
            assertEquals(expected, fixture.preferences.currentDiarySession()?.token)
            assertEquals("synthetic-class", fixture.preferences.currentSession()?.token)
            fixture.assertRequest()
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

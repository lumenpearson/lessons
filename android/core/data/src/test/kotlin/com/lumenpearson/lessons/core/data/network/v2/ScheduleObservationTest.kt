package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.ClassSummary
import com.lumenpearson.lessons.contract.v2.GetScheduleWindowResponse
import com.lumenpearson.lessons.contract.v2.ScheduleWindow
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScheduleObservationTest(private val transport: String) {
    @Test
    fun `successful schedule answers retain the exact coherent credentials seen on the wire`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            enqueueWindow(fixture)
            val observation = CallObservation()
            fixture.schedule.window(2026, null, observation)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            val credentials = checkNotNull(observation.credentials)
            assertEquals(fixture.server.url("/school/").toString(), credentials.baseUrl)
            assertEquals("Bearer ${credentials.classToken}", sent.headers["Authorization"])
            assertEquals("synthetic-device", credentials.classToken)
            assertEquals("synthetic-diary", credentials.diaryToken)
        }
    }

    @Test
    fun `a real refused schedule call keeps its sent identity and observed HTTP status`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                .body("""{"error":{"status":"UNAUTHENTICATED"},"code":"unauthenticated"}""").build())
            val observation = CallObservation()
            val failure = runCatching { fixture.schedule.window(2026, null, observation) }.exceptionOrNull()
            assertTrue(failure is RemoteError)
            assertEquals(401, (failure as RemoteError).httpStatus)
            assertEquals("synthetic-device", observation.credentials?.classToken)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer ${observation.credentials?.classToken}", sent.headers["Authorization"])
        }
    }

    @Test
    fun `a stale conditional owner is refused before sending under a newer token or server`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val current = RequestCredentials(fixture.server.url("/school/").toString(),
                "synthetic-device", "synthetic-diary")
            for (expected in listOf(current.copy(classToken = "synthetic-old"),
                current.copy(baseUrl = "https://other.invalid/"))) {
                enqueueWindow(fixture)
                val observation = CallObservation(expected)
                val failure = runCatching { fixture.schedule.window(2026, "belongs-to-old", observation) }
                    .exceptionOrNull()
                assertTrue(failure is IOException)
                assertSame(observation.failure, failure)
                assertNull(observation.status)
                assertNull(observation.credentials)
            }
            assertEquals(0, fixture.server.requestCount)
        }
    }

    private fun enqueueWindow(fixture: V2BindingFixture) {
        val window = ScheduleWindow.newBuilder().setSchoolClass(ClassSummary.newBuilder().setId(7).setName("7A"))
        fixture.success("""{"window":{"schoolClass":{"id":"7","name":"7A"}}}""",
            GetScheduleWindowResponse.newBuilder().setWindow(window).build())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun transports(): List<Array<String>> = listOf("rest", "connect", "grpc").map { arrayOf(it) }
    }
}

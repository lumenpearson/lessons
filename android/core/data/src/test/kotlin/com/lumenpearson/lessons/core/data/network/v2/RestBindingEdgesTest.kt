package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.JoinFailure
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestBindingEdgesTest {
    @Test
    fun `a bare proxy throttle retains Retry-After without an ErrorInfo body`() = runBlocking {
        V2BindingFixture("rest").use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "901")
                .body("proxy").build())
            val actual = checkNotNull(runCatching { fixture.join.create("DEMO24", null) }.exceptionOrNull())
            assertEquals(JoinFailure.TooManyAttempts(901), JoinFailure.of(actual))
        }
    }

    @Test
    fun `malformed successful responses keep their observed HTTP status`() = runBlocking {
        V2BindingFixture("rest").use { fixture ->
            for ((status, body) in listOf(200 to "not JSON", 201 to "{}", 204 to "")) {
                fixture.server.enqueue(MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
                    .body(body).build())
                val actual = runCatching { fixture.join.create("DEMO24", null) }.exceptionOrNull()
                assertTrue(actual is RemoteError)
                assertEquals(status, (actual as RemoteError).httpStatus)
            }
        }
    }
}

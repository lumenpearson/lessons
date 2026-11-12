package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.Any as ProtoAny
import com.google.rpc.ErrorInfo
import com.google.rpc.Status
import com.lumenpearson.lessons.contract.v2.ClassSummary
import com.lumenpearson.lessons.contract.v2.CreateDeviceRequest
import com.lumenpearson.lessons.contract.v2.CreateDeviceResponse
import com.lumenpearson.lessons.contract.v2.GetScheduleWindowRequest
import com.lumenpearson.lessons.contract.v2.GetScheduleWindowResponse
import com.lumenpearson.lessons.contract.v2.ScheduleDay
import com.lumenpearson.lessons.contract.v2.ScheduleWindow
import com.lumenpearson.lessons.core.data.repository.JoinFailure
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.model.DayKind
import java.time.LocalDate
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.Headers
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class V2BindingsTest(private val transport: String) {
    @Test
    fun `join sends its actual binding without a bearer and returns only a domain session`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"token":"new-token","classId":7,"className":"9A","school":" School "}""",
                CreateDeviceResponse.newBuilder().setToken("new-token").setClassId(7).setClassName("9A")
                    .setSchool(" School ").build())

            assertEquals(Session(7, "9A", "School", "new-token"), fixture.join.create("DEMO24", null))
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertNull(sent.headers["Authorization"])
            assertEquals("73", sent.headers["X-Lessons-Client"])
            assertEquals(if (transport == "rest") "/school/api/v2/devices"
                else "/school/api/rpc/lessons.v2.DeviceService/CreateDevice", sent.url.encodedPath)
            if (transport == "rest") {
                assertEquals("""{"code":"DEMO24"}""", sent.body?.utf8())
            } else {
                val bytes = checkNotNull(sent.body).toByteArray().let {
                    if (transport == "grpc") it.drop(5).toByteArray() else it
                }
                val request = CreateDeviceRequest.parseFrom(bytes)
                assertEquals("DEMO24", request.code)
                assertFalse(request.hasDeviceName())
            }
        }
    }

    @Test
    fun `a changed window keeps its class zone future enum row and timestamp fallback`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val window = ScheduleWindow.newBuilder().setSchoolClass(
                ClassSummary.newBuilder().setId(7).setName(" 9A ").setTimezone("Asia/Yakutsk"),
            ).addDays(ScheduleDay.newBuilder().setDate("2026-11-12").setKindValue(99)).build()
            fixture.success("""{"etag":"wire-tag","window":{"schoolClass":{"id":7,"name":" 9A ",
                "timezone":"Asia/Yakutsk"},"days":[{"date":"2026-11-12","kind":99}]}}""",
                GetScheduleWindowResponse.newBuilder().setEtag("wire-tag").setWindow(window).build(), "http-tag")

            val actual = fixture.schedule.window(2026, "previous") as ScheduleRemoteResult.Changed

            assertEquals(7L, actual.timetable.schoolClass.id)
            assertEquals("9A", actual.timetable.schoolClass.name)
            assertEquals("Asia/Yakutsk", actual.timetable.schoolClass.timeZoneId)
            assertEquals(fixture.clock.millis(), actual.timetable.syncedAtEpochMillis)
            assertEquals(LocalDate.parse("2026-11-12"), actual.timetable.days.single().date)
            assertEquals(DayKind.NORMAL, actual.timetable.days.single().kind)
            assertEquals(if (transport == "rest") "http-tag" else "wire-tag", actual.etag)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-device", sent.headers["Authorization"])
            if (transport == "rest") {
                assertEquals("/school/api/v2/class/scheduleWindows/2026", sent.url.encodedPath)
                assertEquals("previous", sent.headers["If-None-Match"])
            } else {
                val bytes = checkNotNull(sent.body).toByteArray().let {
                    if (transport == "grpc") it.drop(5).toByteArray() else it
                }
                val request = GetScheduleWindowRequest.parseFrom(bytes)
                assertEquals(2026, request.year)
                assertEquals("previous", request.ifNoneMatch)
            }
        }
    }

    @Test
    fun `not modified is a domain answer with no fabricated window`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            if (transport == "rest") {
                fixture.server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "same").build())
            } else {
                fixture.success("", GetScheduleWindowResponse.newBuilder().setNotModified(true).setEtag("same").build())
            }
            assertEquals(ScheduleRemoteResult.NotModified("same"), fixture.schedule.window(2026, "same"))
        }
    }

    @Test
    fun `an answered refusal is decoded before a valid looking success body can become data`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            for (status in listOf(401, 404, 503, 504)) {
                fixture.server.enqueue(MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
                    .body("""{"token":"must-not-escape","classId":7,"className":"9A"}""").build())
                val failure = runCatching { fixture.join.create("DEMO24", null) }.exceptionOrNull()
                assertTrue(failure is RemoteError)
                assertEquals(status, (failure as RemoteError).httpStatus)
            }
        }
    }

    @Test
    fun `real ErrorInfo details and RetryInfo survive each wire encoding into the public classifier`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val info = ErrorInfo.newBuilder().setReason("THROTTLED").putMetadata("retry_after_seconds", "901").build()
            when (transport) {
                "rest" -> fixture.server.enqueue(MockResponse.Builder().code(429)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"error":{"status":"RESOURCE_EXHAUSTED","details":[
                        {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"THROTTLED",
                        "metadata":{"retry_after_seconds":"901"}},
                        {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"901s"}]}}""").build())
                "connect" -> fixture.server.enqueue(MockResponse.Builder().code(429)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"code":"resource_exhausted","message":"synthetic","details":[
                        {"type":"google.rpc.ErrorInfo",
                        "value":"${info.toByteArray().toByteString().base64()}"}]}""").build())
                else -> {
                    val status = Status.newBuilder().setCode(8).addDetails(ProtoAny.newBuilder()
                        .setTypeUrl("type.googleapis.com/google.rpc.ErrorInfo").setValue(info.toByteString())).build()
                    fixture.server.enqueue(MockResponse.Builder().code(200)
                        .addHeader("Content-Type", "application/grpc+proto")
                        .body(Buffer()).trailers(Headers.Builder().add("grpc-status", "8")
                            .add("grpc-status-details-bin", status.toByteArray().toByteString().base64())
                            .build()).build())
                }
            }
            val error = runCatching { fixture.join.create("DEMO24", null) }.exceptionOrNull()
            assertTrue(error is RemoteError)
            assertEquals(JoinFailure.TooManyAttempts(901), JoinFailure.of(checkNotNull(error)))
            if (transport == "rest") assertEquals(Duration.ofSeconds(901), (error as RemoteError).retryDelay)
        }
    }

    @Test
    fun `missing payloads are answered protocol failures rather than empty successful data`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("{}", GetScheduleWindowResponse.getDefaultInstance())
            val error = runCatching { fixture.schedule.window(2026, null) }.exceptionOrNull()
            assertTrue(error is RemoteError)
            assertEquals(200, (error as RemoteError).httpStatus)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

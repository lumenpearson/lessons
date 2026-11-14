package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.Timestamp
import com.lumenpearson.lessons.contract.v2.BellPeriod as ProtoPeriod
import com.lumenpearson.lessons.contract.v2.BellSchedule as ProtoBells
import com.lumenpearson.lessons.contract.v2.ClassDevice
import com.lumenpearson.lessons.contract.v2.GetClassResponse
import com.lumenpearson.lessons.contract.v2.ListClassDevicesResponse
import com.lumenpearson.lessons.contract.v2.SchoolClass
import com.lumenpearson.lessons.contract.v2.UpdateBellScheduleRequest
import com.lumenpearson.lessons.contract.v2.UpdateBellScheduleResponse
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.ManageRepositoryImpl
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ManageTimeRemoteTest(private val transport: String) {
    @Test
    fun `a bell write sends minute precision and keeps its write-specific silenced count`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"schedule":{"id":9,"name":"Main","periods":[
                {"index":1,"startsAt":"09:05","endsAt":"09:50"}]},"silencedLessons":3}""",
                UpdateBellScheduleResponse.newBuilder().setSchedule(ProtoBells.newBuilder().setId(9)
                    .setName("Main").addPeriods(ProtoPeriod.newBuilder().setIndex(1).setStartsAt("09:05")
                        .setEndsAt("09:50"))).setSilencedLessons(3).build())
            val requested = listOf(BellPeriod(1, LocalTime.of(9, 5, 27), LocalTime.of(9, 50, 42)))
            val answer = repository(fixture).writeBellPeriods(9, requested).getOrNull()
            assertEquals(listOf(BellPeriod(1, LocalTime.of(9, 5), LocalTime.of(9, 50))), answer?.schedule?.periods)
            assertEquals(3, answer?.silencedLessons)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-device", sent.headers["Authorization"])
            if (transport == "rest") {
                assertEquals("PATCH", sent.method)
                assertEquals("/school/api/v2/class/bellSchedules/9", sent.url.encodedPath)
                assertEquals("periods", sent.url.queryParameter("updateMask"))
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals(setOf("periods"), body.keys)
                val period = body.getValue("periods").jsonArray.single().jsonObject
                assertEquals("09:05", period.getValue("startsAt").jsonPrimitive.content)
                assertEquals("09:50", period.getValue("endsAt").jsonPrimitive.content)
            } else {
                val bytes = checkNotNull(sent.body).toByteArray().let {
                    if (transport == "grpc") it.drop(5).toByteArray() else it
                }
                val body = UpdateBellScheduleRequest.parseFrom(bytes)
                assertEquals(listOf("periods"), body.updateMask.pathsList)
                assertEquals(9, body.schedule.id)
                assertEquals("", body.schedule.name)
                assertEquals("09:05", body.schedule.getPeriods(0).startsAt)
                assertEquals("09:50", body.schedule.getPeriods(0).endsAt)
            }
        }
    }

    @Test
    fun `a device timestamp uses the same membership class zone across the DST transition`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"schoolClass":{"id":7,"name":"9A","timezone":"Europe/Berlin"}}""",
                GetClassResponse.newBuilder().setSchoolClass(SchoolClass.newBuilder().setId(7).setName("9A")
                    .setTimezone("Europe/Berlin")).build())
            fixture.success("""{"devices":[{"id":8,"deviceName":" phone ","role":99,
                "createdAt":"2026-03-29T01:30:00Z"}]}""", ListClassDevicesResponse.newBuilder()
                    .addDevices(ClassDevice.newBuilder().setId(8).setDeviceName(" phone ").setRoleValue(99)
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.parse("2026-03-29T01:30:00Z")
                            .epochSecond))).build())
            val answer = repository(fixture).devices().getOrNull()
            assertEquals(1, answer?.size)
            val device = checkNotNull(answer).single()
            assertEquals("phone", device.deviceName)
            assertNull(device.role)
            assertEquals(LocalDateTime.parse("2026-03-29T03:30:00"), device.createdAt)
            assertNull(device.lastSeenAt)
            assertNull(device.linkedAt)
            assertEquals(2, fixture.server.requestCount)
            val first = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            val second = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-device", first.headers["Authorization"])
            assertEquals("Bearer synthetic-device", second.headers["Authorization"])
            assertEquals(if (transport == "rest") "/school/api/v2/class"
                else "/school/api/rpc/lessons.v2.ClassService/GetClass", first.url.encodedPath)
            assertEquals(if (transport == "rest") "/school/api/v2/class/devices"
                else "/school/api/rpc/lessons.v2.ClassDeviceService/ListClassDevices", second.url.encodedPath)
        }
    }

    private fun repository(fixture: V2BindingFixture) = ManageRepositoryImpl(
        fixture.manage,
        ioDispatcher = Dispatchers.Unconfined,
    )

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

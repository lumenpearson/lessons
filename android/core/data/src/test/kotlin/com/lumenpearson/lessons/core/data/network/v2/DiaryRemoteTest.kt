package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.BatchUpdateCorrectionsRequest
import com.lumenpearson.lessons.contract.v2.BatchUpdateCorrectionsResponse
import com.lumenpearson.lessons.contract.v2.DiaryCorrection
import com.lumenpearson.lessons.contract.v2.DiaryLesson as ProtoLesson
import com.lumenpearson.lessons.contract.v2.DiaryScheduleDay
import com.lumenpearson.lessons.contract.v2.DiaryStudent as ProtoStudent
import com.lumenpearson.lessons.contract.v2.ListScheduleDaysRequest
import com.lumenpearson.lessons.contract.v2.ListScheduleDaysResponse
import com.lumenpearson.lessons.contract.v2.ListStudentsResponse
import com.lumenpearson.lessons.core.data.diary.MemoryDiaryStore
import com.lumenpearson.lessons.core.data.diary.UnusedSignIn
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DiaryRemoteTest(private val transport: String) {
    @Test
    fun `student envelopes retain exact int64 ids and normalize names at the domain boundary`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success(
                """{"students":[{"id":"9007199254740993","firstName":" Ivan ","lastName":" Petrov ",
                    "school":" School ","className":" 9A "}]}""",
                ListStudentsResponse.newBuilder().addStudents(ProtoStudent.newBuilder().setId(StudentId)
                    .setFirstName(" Ivan ").setLastName(" Petrov ").setSchool(" School ").setClassName(" 9A "))
                    .build(),
            )
            assertEquals(listOf(DiaryStudent(StudentId, "Ivan", "Petrov", null, "Petrov Ivan", "School", "9A")),
                repository(fixture).students().getOrNull())
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-diary", sent.headers["Authorization"])
            assertEquals(if (transport == "rest") "/school/api/v2/diary/students"
                else "/school/api/rpc/lessons.v2.DiaryService/ListStudents", sent.url.encodedPath)
        }
    }

    @Test
    fun `schedule days supply each lesson date and preserve opaque correction targets`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val day = LocalDate.parse("2026-11-13")
            fixture.success(
                """{"scheduleDays":[{"date":"not-a-date","lessons":[{"subject":"discard"}]},
                    {"date":"2026-11-13","lessons":[{"number":2,"subject":" Maths ","startsAt":"09:05",
                    "room":" ","teacher":" T ","homework":" Homework ","target":" lesson&x=1 "}]}]}""",
                ListScheduleDaysResponse.newBuilder()
                    .addScheduleDays(DiaryScheduleDay.newBuilder().setDate("not-a-date")
                        .addLessons(ProtoLesson.newBuilder().setSubject("discard")))
                    .addScheduleDays(DiaryScheduleDay.newBuilder().setDate("2026-11-13")
                        .addLessons(ProtoLesson.newBuilder().setNumber(2).setSubject(" Maths ").setStartsAt("09:05")
                            .setRoom(" ").setTeacher(" T ").setHomework(" Homework ").setTarget(" lesson&x=1 ")))
                    .build(),
            )
            assertEquals(listOf(DiaryLesson(day, 2, "Maths", LocalTime.of(9, 5), null,
                null, "T", "Homework", null, target = " lesson&x=1 ")),
                repository(fixture).schedule(StudentId, day, day).getOrNull())
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-diary", sent.headers["Authorization"])
            if (transport == "rest") {
                assertEquals("/school/api/v2/diary/students/9007199254740993/scheduleDays", sent.url.encodedPath)
                assertEquals("2026-11-13", sent.url.queryParameter("startDate"))
                assertEquals("2026-11-13", sent.url.queryParameter("endDate"))
            } else {
                val request = ListScheduleDaysRequest.parseFrom(requestBytes(sent))
                assertEquals(StudentId, request.studentId)
                assertEquals("2026-11-13", request.startDate)
                assertEquals("2026-11-13", request.endDate)
            }
        }
    }

    @Test
    fun `a correction uses the v2 batch method without rewriting its target or optional original`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val target = " homework&odd+/?= "
            fixture.success("""{"corrections":[{"target":" homework&odd+/?= ","field":"text","value":" new "}]}""",
                BatchUpdateCorrectionsResponse.newBuilder().addCorrections(DiaryCorrection.newBuilder()
                    .setTarget(target).setField("text").setValue(" new ")).build())
            assertTrue(repository(fixture).correct(StudentId, target, DiaryField.TEXT, " new ", "").isSuccess)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-diary", sent.headers["Authorization"])
            assertEquals("POST", sent.method)
            if (transport == "rest") {
                assertEquals("/school/api/v2/diary/students/9007199254740993/corrections:batchUpdate",
                    sent.url.encodedPath)
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertFalse(body.containsKey("studentId"))
                val change = body.getValue("corrections").jsonArray.single().jsonObject
                assertEquals(target, change.getValue("target").jsonPrimitive.content)
                assertEquals(" new ", change.getValue("value").jsonPrimitive.content)
                assertEquals("", change.getValue("original").jsonPrimitive.content)
            } else {
                val request = BatchUpdateCorrectionsRequest.parseFrom(requestBytes(sent))
                assertEquals(StudentId, request.studentId)
                assertEquals(target, request.getCorrections(0).target)
                assertEquals(" new ", request.getCorrections(0).value)
                assertTrue(request.getCorrections(0).hasOriginal())
                assertEquals("", request.getCorrections(0).original)
            }
        }
    }

    private fun repository(fixture: V2BindingFixture) = DiaryRepositoryImpl(
        api = fixture.diary,
        store = MemoryDiaryStore(),
        diarySignIn = UnusedSignIn,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun requestBytes(sent: mockwebserver3.RecordedRequest): ByteArray =
        checkNotNull(sent.body).toByteArray().let { if (transport == "grpc") it.drop(5).toByteArray() else it }

    companion object {
        private const val StudentId = 9_007_199_254_740_993L
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

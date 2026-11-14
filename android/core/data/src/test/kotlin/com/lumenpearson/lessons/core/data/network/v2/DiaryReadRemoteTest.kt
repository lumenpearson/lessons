package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.ClearCorrectionsRequest
import com.lumenpearson.lessons.contract.v2.ClearCorrectionsResponse
import com.lumenpearson.lessons.contract.v2.DiaryCorrection
import com.lumenpearson.lessons.contract.v2.DiaryHomework as ProtoHomework
import com.lumenpearson.lessons.contract.v2.DiaryMark as ProtoMark
import com.lumenpearson.lessons.contract.v2.DiaryPeriod as ProtoPeriod
import com.lumenpearson.lessons.contract.v2.ListCorrectionsResponse
import com.lumenpearson.lessons.contract.v2.ListDiaryHomeworkRequest
import com.lumenpearson.lessons.contract.v2.ListDiaryHomeworkResponse
import com.lumenpearson.lessons.contract.v2.ListMarksRequest
import com.lumenpearson.lessons.contract.v2.ListMarksResponse
import com.lumenpearson.lessons.contract.v2.ListPeriodsRequest
import com.lumenpearson.lessons.contract.v2.ListPeriodsResponse
import com.lumenpearson.lessons.contract.v2.ResetCorrectionsRequest
import com.lumenpearson.lessons.contract.v2.ResetCorrectionsResponse
import com.lumenpearson.lessons.core.data.diary.MemoryDiaryStore
import com.lumenpearson.lessons.core.data.diary.UnusedSignIn
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.data.repository.DiaryOverrideRecord
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DiaryReadRemoteTest(private val transport: String) {
    @Test
    fun `homework envelopes retain ids and opaque targets while dropping malformed rows`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"homework":[{"dueDate":"bad","text":"discard"},
                {"dueDate":"2026-11-13","text":" "},{"id":"9007199254740993","dueDate":"2026-11-13",
                "subject":" Maths ","text":" Read ","teacher":" ","target":" key&/= "}]}""",
                ListDiaryHomeworkResponse.newBuilder().addHomework(ProtoHomework.newBuilder()
                    .setDueDate("bad").setText("discard")).addHomework(ProtoHomework.newBuilder()
                    .setDueDate("2026-11-13").setText(" ")).addHomework(ProtoHomework.newBuilder()
                    .setId(StudentId).setDueDate("2026-11-13").setSubject(" Maths ").setText(" Read ")
                    .setTeacher(" ").setTarget(" key&/= ")).build())
            assertEquals(listOf(DiaryHomework(StudentId, Day, "Maths", "Read", null, target = " key&/= ")),
                repository(fixture).homework(StudentId, Day, Day).getOrNull())
            val sent = request(fixture, "homework", "ListDiaryHomework")
            if (transport == "rest") {
                assertEquals("2026-11-13", sent.url.queryParameter("startDate"))
                assertEquals("2026-11-13", sent.url.queryParameter("endDate"))
            } else {
                val body = ListDiaryHomeworkRequest.parseFrom(bytes(sent))
                assertEquals(StudentId, body.studentId)
                assertEquals("2026-11-13", body.startDate)
                assertEquals("2026-11-13", body.endDate)
            }
        }
    }

    @Test
    fun `future mark kinds remain visible without entering numeric averages`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"marks":[{"id":"9007199254740993","subjectId":"9007199254740993",
                "subject":" Maths ","date":"bad","value":" 5 ","kind":99,"reason":" Quiz "}]}""",
                ListMarksResponse.newBuilder().addMarks(ProtoMark.newBuilder().setId(StudentId)
                    .setSubjectId(StudentId).setSubject(" Maths ").setDate("bad").setValue(" 5 ")
                    .setKindValue(99).setReason(" Quiz ")).build())
            val answer = repository(fixture).grades(StudentId, Day, Day).getOrNull()
            assertEquals(1, answer?.size)
            val mark = checkNotNull(answer).single()
            assertEquals(StudentId, mark.id)
            assertEquals(StudentId, mark.subjectId)
            assertEquals("Maths", mark.subject)
            assertEquals("5", mark.value)
            assertEquals("Quiz", mark.reason)
            assertEquals(DiaryMarkKind.OTHER, mark.kind)
            assertNull(mark.date)
            assertNull(mark.numericValue)
            val sent = request(fixture, "marks", "ListMarks")
            if (transport == "rest") {
                assertEquals("2026-11-13", sent.url.queryParameter("startDate"))
                assertEquals("2026-11-13", sent.url.queryParameter("endDate"))
            } else {
                val body = ListMarksRequest.parseFrom(bytes(sent))
                assertEquals(StudentId, body.studentId)
                assertEquals("2026-11-13", body.startDate)
                assertEquals("2026-11-13", body.endDate)
            }
        }
    }

    @Test
    fun `period envelopes keep an unreadable optional edge absent`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"periods":[{"id":"9007199254740993","name":" Quarter ",
                "startsOn":"bad","endsOn":"2026-11-13","isCurrent":true}]}""",
                ListPeriodsResponse.newBuilder().addPeriods(ProtoPeriod.newBuilder().setId(StudentId)
                    .setName(" Quarter ").setStartsOn("bad").setEndsOn("2026-11-13").setIsCurrent(true)).build())
            assertEquals(listOf(DiaryPeriod(StudentId, "Quarter", null, Day, true)),
                repository(fixture).periods(StudentId).getOrNull())
            val sent = request(fixture, "periods", "ListPeriods")
            if (transport != "rest") assertEquals(StudentId, ListPeriodsRequest.parseFrom(bytes(sent)).studentId)
        }
    }

    @Test
    fun `correction lists preserve exact keys and hide fields this app cannot reset`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"corrections":[{"target":" key&/= ","field":"text","value":" new ",
                "originalWhenWritten":" old "},{"target":"other","field":"future","value":"x"}]}""",
                ListCorrectionsResponse.newBuilder().addCorrections(DiaryCorrection.newBuilder()
                    .setTarget(" key&/= ").setField("text").setValue(" new ").setOriginalWhenWritten(" old "))
                    .addCorrections(DiaryCorrection.newBuilder().setTarget("other").setField("future").setValue("x"))
                    .build())
            assertEquals(listOf(DiaryOverrideRecord(" key&/= ", DiaryField.TEXT, " new ", "old")),
                repository(fixture).overrides(StudentId).getOrNull())
            val sent = request(fixture, "corrections", "ListCorrections")
            assertEquals(if (transport == "rest") "GET" else "POST", sent.method)
        }
    }

    @Test
    fun `a reset sends the exact key in a body without the path-bound student id`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("{}", ResetCorrectionsResponse.getDefaultInstance())
            assertTrue(repository(fixture).reset(StudentId, " key&/= ", DiaryField.TEXT).isSuccess)
            val sent = request(fixture, "corrections:reset", "ResetCorrections")
            assertEquals("POST", sent.method)
            if (transport == "rest") {
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals(setOf("corrections"), body.keys)
                val key = body.getValue("corrections").jsonArray.single().jsonObject
                assertEquals(" key&/= ", key.getValue("target").jsonPrimitive.content)
                assertEquals("text", key.getValue("field").jsonPrimitive.content)
            } else {
                val body = ResetCorrectionsRequest.parseFrom(bytes(sent))
                assertEquals(StudentId, body.studentId)
                assertEquals(" key&/= ", body.getCorrections(0).target)
                assertEquals("text", body.getCorrections(0).field)
            }
        }
    }

    @Test
    fun `reset all uses the v2 clear action and preserves diary bearer isolation`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("{}", ClearCorrectionsResponse.getDefaultInstance())
            assertTrue(repository(fixture).resetAll(StudentId).isSuccess)
            val sent = request(fixture, "corrections:clear", "ClearCorrections")
            assertEquals("POST", sent.method)
            if (transport == "rest") assertEquals("{}", sent.body?.utf8())
            else assertEquals(StudentId, ClearCorrectionsRequest.parseFrom(bytes(sent)).studentId)
        }
    }

    private fun repository(fixture: V2BindingFixture) = DiaryRepositoryImpl(
        fixture.diary,
        MemoryDiaryStore(), UnusedSignIn, ioDispatcher = Dispatchers.Unconfined,
    )

    private fun request(fixture: V2BindingFixture, suffix: String, method: String): mockwebserver3.RecordedRequest {
        assertEquals(1, fixture.server.requestCount)
        val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("Bearer synthetic-diary", sent.headers["Authorization"])
        assertEquals("73", sent.headers["X-Lessons-Client"])
        assertEquals(if (transport == "rest") "/school/api/v2/diary/students/9007199254740993/$suffix"
            else "/school/api/rpc/lessons.v2.DiaryService/$method", sent.url.encodedPath)
        return sent
    }

    private fun bytes(sent: mockwebserver3.RecordedRequest): ByteArray =
        checkNotNull(sent.body).toByteArray().let { if (transport == "grpc") it.drop(5).toByteArray() else it }

    companion object {
        private val Day = LocalDate.parse("2026-11-13")
        private const val StudentId = 9_007_199_254_740_993L
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

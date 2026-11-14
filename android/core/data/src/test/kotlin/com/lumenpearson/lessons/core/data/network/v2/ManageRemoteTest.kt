package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.JoinMode
import com.lumenpearson.lessons.contract.v2.GetClassResponse
import com.lumenpearson.lessons.contract.v2.GetSubjectRequest
import com.lumenpearson.lessons.contract.v2.GetSubjectResponse
import com.lumenpearson.lessons.contract.v2.SchoolClass
import com.lumenpearson.lessons.contract.v2.Subject
import com.lumenpearson.lessons.contract.v2.UpdateClassRequest
import com.lumenpearson.lessons.contract.v2.UpdateClassResponse
import com.lumenpearson.lessons.contract.v2.UpdateSubjectRequest
import com.lumenpearson.lessons.contract.v2.UpdateSubjectResponse
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.ManageRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ManageRemoteTest(private val transport: String) {
    @Test
    fun `a class edit clears only its changed nullable field and preserves concurrent fields`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"schoolClass":{"id":7,"name":"9A","city":"New city",
                "timezone":"Europe/Moscow","joinMode":"JOIN_MODE_OPEN"}}""",
                UpdateClassResponse.newBuilder().setSchoolClass(SchoolClass.newBuilder().setId(7)
                    .setName("9A").setCity("New city").setTimezone("Europe/Moscow")
                    .setJoinMode(JoinMode.JOIN_MODE_OPEN)).build())
            val before = card()
            val edit = ClassEdit(before.name, " ", " New city ", before.timezone)
            assertEquals(before.copy(school = null, city = "New city"),
                repository(fixture).updateClass(before, edit).getOrNull())
            val sent = request(fixture)
            if (transport == "rest") {
                assertEquals("PATCH", sent.method)
                assertEquals("/school/api/v2/class", sent.url.encodedPath)
                assertEquals(setOf("school", "city"), checkNotNull(sent.url.queryParameter("updateMask"))
                    .split(',').toSet())
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals(setOf("city"), body.keys)
                assertEquals("New city", body.getValue("city").jsonPrimitive.content)
            } else {
                val body = UpdateClassRequest.parseFrom(bytes(sent))
                assertEquals(setOf("school", "city"), body.updateMask.pathsList.toSet())
                assertFalse(body.schoolClass.hasSchool())
                assertEquals("New city", body.schoolClass.city)
                assertEquals("", body.schoolClass.name)
                assertEquals("", body.schoolClass.timezone)
                assertEquals(JoinMode.JOIN_MODE_UNSPECIFIED, body.schoolClass.joinMode)
            }
        }
    }

    @Test
    fun `the join-mode toggle sends no stale class form fields`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"schoolClass":{"id":7,"name":"9A","school":"School","city":"City",
                "timezone":"Europe/Moscow","joinMode":"JOIN_MODE_INVITE"}}""",
                UpdateClassResponse.newBuilder().setSchoolClass(SchoolClass.newBuilder().setId(7)
                    .setName("9A").setSchool("School").setCity("City").setTimezone("Europe/Moscow")
                    .setJoinMode(JoinMode.JOIN_MODE_INVITE)).build())
            assertEquals(ClassJoinMode.INVITE, repository(fixture).setJoinMode(ClassJoinMode.INVITE)
                .getOrNull()?.joinMode)
            val sent = request(fixture)
            if (transport == "rest") {
                assertEquals("joinMode", sent.url.queryParameter("updateMask"))
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals(setOf("joinMode"), body.keys)
                assertEquals("JOIN_MODE_INVITE", body.getValue("joinMode").jsonPrimitive.content)
            } else {
                val body = UpdateClassRequest.parseFrom(bytes(sent))
                assertEquals(listOf("join_mode"), body.updateMask.pathsList)
                assertEquals(JoinMode.JOIN_MODE_INVITE, body.schoolClass.joinMode)
                assertEquals("", body.schoolClass.name)
                assertEquals("", body.schoolClass.timezone)
            }
        }
    }

    @Test
    fun `a subject edit uses resource body and mask to distinguish unchanged and cleared fields`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"subject":{"id":9,"name":"Maths","teacher":"New teacher","color":"#112233"},
                "moved":4}""", UpdateSubjectResponse.newBuilder().setSubject(Subject.newBuilder()
                    .setId(9).setName("Maths").setTeacher("New teacher").setColor("#112233")).setMoved(4).build())
            val before = ManagedSubject(9, "Maths", "M", "Old teacher", "#112233")
            val form = SubjectForm(" Maths ", " ", " New teacher ", "#112233")
            val answer = repository(fixture).updateSubject(before, form).getOrNull()
            assertEquals(before.copy(shortName = null, teacher = "New teacher"), answer?.subject)
            assertEquals(4, answer?.moved)
            val sent = request(fixture)
            if (transport == "rest") {
                assertEquals("PATCH", sent.method)
                assertEquals("/school/api/v2/class/subjects/9", sent.url.encodedPath)
                assertEquals(setOf("shortName", "teacher"), checkNotNull(sent.url.queryParameter("updateMask"))
                    .split(',').toSet())
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals(setOf("teacher"), body.keys)
            } else {
                val body = UpdateSubjectRequest.parseFrom(bytes(sent))
                assertEquals(setOf("short_name", "teacher"), body.updateMask.pathsList.toSet())
                assertEquals(9, body.subject.id)
                assertFalse(body.subject.hasShortName())
                assertEquals("New teacher", body.subject.teacher)
                assertFalse(body.subject.hasColor())
                assertEquals("", body.subject.name)
            }
        }
    }

    @Test
    fun `an unchanged class form reads fresh data instead of sending an empty update mask`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"schoolClass":{"id":7,"name":"9A","school":"School","city":"City",
                "timezone":"Europe/Moscow","joinMode":"JOIN_MODE_OPEN"}}""",
                GetClassResponse.newBuilder().setSchoolClass(SchoolClass.newBuilder().setId(7)
                    .setName("9A").setSchool("School").setCity("City").setTimezone("Europe/Moscow")
                    .setJoinMode(JoinMode.JOIN_MODE_OPEN)).build())
            val before = card()
            assertEquals(before, repository(fixture).updateClass(before,
                ClassEdit(before.name, before.school, before.city, before.timezone)).getOrNull())
            val sent = request(fixture)
            assertEquals(if (transport == "rest") "GET" else "POST", sent.method)
            assertEquals(if (transport == "rest") "/school/api/v2/class"
                else "/school/api/rpc/lessons.v2.ClassService/GetClass", sent.url.encodedPath)
            assertEquals(null, sent.url.queryParameter("updateMask"))
        }
    }

    @Test
    fun `an unchanged subject form reads its resource instead of overwriting every field`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"subject":{"id":9,"name":"Maths","shortName":"M",
                "teacher":"Teacher","color":"#112233"}}""",
                GetSubjectResponse.newBuilder().setSubject(Subject.newBuilder().setId(9)
                    .setName("Maths").setShortName("M").setTeacher("Teacher").setColor("#112233")).build())
            val before = ManagedSubject(9, "Maths", "M", "Teacher", "#112233")
            val answer = repository(fixture).updateSubject(before,
                SubjectForm(before.name, before.shortName, before.teacher, before.color)).getOrNull()
            assertEquals(before, answer?.subject)
            assertEquals(0, answer?.moved)
            val sent = request(fixture)
            assertEquals(if (transport == "rest") "GET" else "POST", sent.method)
            assertEquals(if (transport == "rest") "/school/api/v2/class/subjects/9"
                else "/school/api/rpc/lessons.v2.SubjectService/GetSubject", sent.url.encodedPath)
            if (transport != "rest") assertEquals(9, GetSubjectRequest.parseFrom(bytes(sent)).subjectId)
        }
    }

    @Test
    fun `an unrepresentable management id fails before any truncating wire call`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(400).body("{}").build())
            assertTrue(repository(fixture).deleteSubject(Long.MAX_VALUE).isFailure)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    private fun repository(fixture: V2BindingFixture) = ManageRepositoryImpl(
        fixture.manage,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun card() = ManagedClass(7, "9A", "School", "City", "Europe/Moscow", "Europe/Moscow", "",
        ClassJoinMode.OPEN, 0, 0, 0, null, false)

    private fun request(fixture: V2BindingFixture): mockwebserver3.RecordedRequest {
        assertEquals(1, fixture.server.requestCount)
        val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("Bearer synthetic-device", sent.headers["Authorization"])
        assertEquals("73", sent.headers["X-Lessons-Client"])
        return sent
    }

    private fun bytes(sent: mockwebserver3.RecordedRequest): ByteArray =
        checkNotNull(sent.body).toByteArray().let { if (transport == "grpc") it.drop(5).toByteArray() else it }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

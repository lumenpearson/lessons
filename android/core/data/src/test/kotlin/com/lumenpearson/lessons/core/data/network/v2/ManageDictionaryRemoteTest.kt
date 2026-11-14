package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.BellSchedule
import com.lumenpearson.lessons.contract.v2.CreateBellScheduleResponse
import com.lumenpearson.lessons.contract.v2.CreateSubjectRequest
import com.lumenpearson.lessons.contract.v2.CreateSubjectResponse
import com.lumenpearson.lessons.contract.v2.DeleteBellScheduleResponse
import com.lumenpearson.lessons.contract.v2.DeleteSubjectResponse
import com.lumenpearson.lessons.contract.v2.ListBellSchedulesResponse
import com.lumenpearson.lessons.contract.v2.ListSubjectsResponse
import com.lumenpearson.lessons.contract.v2.Subject
import com.lumenpearson.lessons.contract.v2.UpdateBellScheduleResponse
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ManageDictionaryRemoteTest(private val transport: String) {
    @Test
    fun `subject list creation and removal use their canonical services and normalized form`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val remote = fixture.management()
            val subject = Subject.newBuilder().setId(3).setName(" Physics ").setShortName(" Ph ").build()
            fixture.success("""{"subjects":[{"id":3,"name":" Physics ","shortName":" Ph "}]}""",
                ListSubjectsResponse.newBuilder().addSubjects(subject).build())
            val rows = remote.subjects().getOrNull()
            assertEquals(1, rows?.size)
            assertEquals("Physics", rows?.single()?.name)
            fixture.managementRequest("class/subjects", "SubjectService/ListSubjects")

            fixture.success("""{"subject":{"id":3,"name":"Physics","shortName":"Ph"}}""",
                CreateSubjectResponse.newBuilder().setSubject(subject).build())
            val created = remote.createSubject(SubjectForm(" Physics ", " Ph ", "  ", null)).getOrNull()
            assertEquals("Ph", created?.subject?.shortName)
            val sent = fixture.managementRequest("class/subjects", "SubjectService/CreateSubject", "POST")
            if (transport == "rest") {
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals("Physics", body.getValue("name").jsonPrimitive.content)
            } else {
                assertEquals("Physics", CreateSubjectRequest.parseFrom(fixture.managementBytes(sent)).subject.name)
            }

            fixture.success("{}", DeleteSubjectResponse.getDefaultInstance())
            assertTrue(remote.deleteSubject(3).isSuccess)
            fixture.managementRequest("class/subjects/3", "SubjectService/DeleteSubject", "DELETE")
        }
    }

    @Test
    fun `bell list creation rename and removal all use the selected v2 binding`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val remote = fixture.management()
            val schedule = BellSchedule.newBuilder().setId(4).setName(" Short ").build()
            fixture.success("""{"schedules":[{"id":4,"name":" Short "}]}""",
                ListBellSchedulesResponse.newBuilder().addSchedules(schedule).build())
            assertEquals("Short", remote.bells().getOrNull()?.singleOrNull()?.name)
            fixture.managementRequest("class/bellSchedules", "BellService/ListBellSchedules")
            fixture.success("""{"schedule":{"id":4,"name":" Short "}}""",
                CreateBellScheduleResponse.newBuilder().setSchedule(schedule).build())
            assertEquals(4L, remote.createBellSchedule(" Short ", emptyList()).getOrNull()?.id)
            fixture.managementRequest("class/bellSchedules", "BellService/CreateBellSchedule", "POST")
            fixture.success("""{"schedule":{"id":4,"name":" Renamed "}}""",
                UpdateBellScheduleResponse.newBuilder().setSchedule(schedule.toBuilder().setName(" Renamed ")).build())
            assertEquals("Renamed", remote.renameBellSchedule(4, " Renamed ").getOrNull()?.name)
            fixture.managementRequest("class/bellSchedules/4", "BellService/UpdateBellSchedule", "PATCH")
            fixture.success("{}", DeleteBellScheduleResponse.getDefaultInstance())
            assertTrue(remote.deleteBellSchedule(4).isSuccess)
            fixture.managementRequest("class/bellSchedules/4", "BellService/DeleteBellSchedule", "DELETE")
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.DeleteClassRequest
import com.lumenpearson.lessons.contract.v2.DeleteClassResponse
import com.lumenpearson.lessons.contract.v2.GetTimetableResponse
import com.lumenpearson.lessons.contract.v2.ImportConflict
import com.lumenpearson.lessons.contract.v2.ImportTimetableRequest
import com.lumenpearson.lessons.contract.v2.ImportTimetableResponse
import com.lumenpearson.lessons.contract.v2.ListSchoolsRequest
import com.lumenpearson.lessons.contract.v2.ListSchoolsResponse
import com.lumenpearson.lessons.contract.v2.School
import com.lumenpearson.lessons.contract.v2.Timetable
import com.lumenpearson.lessons.core.data.network.NetworkModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ManageTimetableDirectoryTest(private val transport: String) {
    @Test
    fun `export and conflict preview preserve text rejected rows and meaningful counts`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val repository = fixture.management()
            fixture.success("""{"timetable":{"text":"exact export\n","lessons":3}}""",
                GetTimetableResponse.newBuilder().setTimetable(Timetable.newBuilder()
                    .setText("exact export\n").setLessons(3)).build())
            assertEquals("exact export\n", repository.timetable().getOrNull()?.text)
            fixture.managementRequest("class/timetable", "TimetableService/GetTimetable")
            fixture.success("""{"applied":false,"days":[1,2],"lessons":3,"bells":2,
                "conflicts":[{"weekday":1,"existing":3,"incoming":4}],"rejected":[" raw bad line "]}""",
                ImportTimetableResponse.newBuilder().setApplied(false).addDays(1).addDays(2)
                    .setLessons(3).setBells(2).addConflicts(ImportConflict.newBuilder().setWeekday(1)
                        .setExisting(3).setIncoming(4)).addRejected(" raw bad line ").build())
            val answer = repository.importTimetable(" pasted\ntext ", replace = false).getOrNull()
            assertEquals(false, answer?.applied)
            assertEquals(listOf(1, 2), answer?.days)
            assertEquals(3, answer?.lessons)
            assertEquals(2, answer?.bells)
            assertEquals(4, answer?.conflicts?.singleOrNull()?.incoming)
            assertEquals(listOf("raw bad line"), answer?.rejected)
            val sent = fixture.managementRequest("class/timetable:import", "TimetableService/ImportTimetable", "POST")
            if (transport == "rest") {
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals(" pasted\ntext ", body.getValue("text").jsonPrimitive.content)
            } else {
                val body = ImportTimetableRequest.parseFrom(fixture.managementBytes(sent))
                assertEquals(" pasted\ntext ", body.text)
                assertFalse(body.replace)
                assertFalse(body.validateOnly)
            }
        }
    }

    @Test
    fun `management school search is device authenticated and retains its truncated local page`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"schools":[{"name":"School","fullName":"","city":"Town","active":true},
                {"name":""}],"totalSize":6,"truncated":true}""", ListSchoolsResponse.newBuilder()
                .addSchools(School.newBuilder().setName("School").setCity("Town").setActive(true))
                .addSchools(School.getDefaultInstance()).setTotalSize(6).setTruncated(true).build())
            val page = fixture.management().searchSchools("  Lyceum ").getOrNull()
            assertEquals(1, page?.items?.size)
            assertEquals("School", page?.items?.single()?.fullName)
            assertEquals(6, page?.total)
            assertEquals(true, page?.truncated)
            assertEquals(1, page?.page)
            assertEquals(1, page?.pages)
            val sent = fixture.managementRequest("schools", "DirectoryService/ListSchools")
            if (transport == "rest") {
                assertEquals("Lyceum", sent.url.queryParameter("query"))
                assertEquals("20", sent.url.queryParameter("pageSize"))
            } else {
                val body = ListSchoolsRequest.parseFrom(fixture.managementBytes(sent))
                assertEquals("Lyceum", body.query)
                assertEquals(20, body.pageSize)
            }
        }
    }

    @Test
    fun `explicit class removal sends its confirmation only to the fixture server`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("{}", DeleteClassResponse.getDefaultInstance())
            assertTrue(fixture.management().deleteClass(" 9A ").isSuccess)
            val sent = fixture.managementRequest("class", "ClassService/DeleteClass", "DELETE")
            if (transport == "rest") {
                assertEquals("9A", sent.url.queryParameter("confirmation"))
            } else {
                assertEquals("9A", DeleteClassRequest.parseFrom(fixture.managementBytes(sent)).confirmation)
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

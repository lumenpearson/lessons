package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.Timestamp
import com.lumenpearson.lessons.contract.v2.AccessRequest
import com.lumenpearson.lessons.contract.v2.ApproveAccessRequestRequest
import com.lumenpearson.lessons.contract.v2.ApproveAccessRequestResponse
import com.lumenpearson.lessons.contract.v2.AuditEntry
import com.lumenpearson.lessons.contract.v2.ClassDevice
import com.lumenpearson.lessons.contract.v2.ClassStats
import com.lumenpearson.lessons.contract.v2.DeclineAccessRequestResponse
import com.lumenpearson.lessons.contract.v2.GetClassStatsResponse
import com.lumenpearson.lessons.contract.v2.ListAccessRequestsResponse
import com.lumenpearson.lessons.contract.v2.ListAuditEntriesRequest
import com.lumenpearson.lessons.contract.v2.ListAuditEntriesResponse
import com.lumenpearson.lessons.contract.v2.RevokeClassDeviceResponse
import com.lumenpearson.lessons.contract.v2.Role
import com.lumenpearson.lessons.contract.v2.RoleCount
import com.lumenpearson.lessons.contract.v2.UnlinkClassDeviceResponse
import com.lumenpearson.lessons.core.data.repository.AuditPage
import com.lumenpearson.lessons.core.data.repository.ClassRole
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ManageMembersReportsTest(private val transport: String) {
    @Test
    fun `revoke and unlink return the device with class-local UTC timestamps`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val repository = fixture.management()
            val device = ClassDevice.newBuilder().setId(8).setDeviceName(" Phone ").setCreatedAt(stamp()).build()
            fixture.classZone()
            fixture.success("""{"device":{"id":8,"deviceName":" Phone ","revoked":true,
                "createdAt":"2026-03-29T01:30:00Z"}}""", RevokeClassDeviceResponse.newBuilder()
                .setDevice(device.toBuilder().setRevoked(true)).build())
            val revoked = repository.revokeDevice(8).getOrNull()
            assertEquals(true, revoked?.revoked)
            assertEquals("Phone", revoked?.deviceName)
            assertEquals(LocalDateTime.parse("2026-03-29T03:30:00"), revoked?.createdAt)
            fixture.managementRequest("class", "ClassService/GetClass")
            fixture.managementRequest("class/devices/8:revoke", "ClassDeviceService/RevokeClassDevice", "POST")
            fixture.classZone()
            fixture.success("""{"device":{"id":8,"createdAt":"2026-03-29T01:30:00Z"}}""",
                UnlinkClassDeviceResponse.newBuilder().setDevice(device).build())
            val unlinked = repository.unlinkDevice(8).getOrNull()
            assertEquals(false, unlinked?.linked)
            assertEquals(false, unlinked?.revoked)
            fixture.managementRequest("class", "ClassService/GetClass")
            fixture.managementRequest("class/devices/8:unlink", "ClassDeviceService/UnlinkClassDevice", "POST")
        }
    }

    @Test
    fun `request list and both decisions preserve method-owned status and unknown role fallback`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val repository = fixture.management()
            fixture.classZone()
            fixture.success("""{"accessRequests":[{"id":5,"who":" Person ","requestedRole":99,
                "createdAt":"2026-03-29T01:30:00Z"}]}""", ListAccessRequestsResponse.newBuilder()
                .addAccessRequests(AccessRequest.newBuilder().setId(5).setWho(" Person ")
                    .setRequestedRoleValue(99).setCreatedAt(stamp())).build())
            val rows = repository.requests().getOrNull()
            assertEquals(1, rows?.size)
            assertEquals("Person", rows?.single()?.who)
            assertNull(rows?.single()?.requestedRole)
            assertEquals(LocalDateTime.parse("2026-03-29T03:30:00"), rows?.single()?.createdAt)
            fixture.managementRequest("class", "ClassService/GetClass")
            fixture.managementRequest("class/accessRequests", "AccessRequestService/ListAccessRequests")
            fixture.success("""{"requestId":5,"role":"ROLE_EDITOR","who":" Person "}""",
                ApproveAccessRequestResponse.newBuilder().setRequestId(5).setRole(Role.ROLE_EDITOR)
                    .setWho(" Person ").build())
            val approved = repository.approveRequest(5).getOrNull()
            assertEquals(true, approved?.approved)
            assertEquals(ClassRole.EDITOR, approved?.role)
            val sent = fixture.managementRequest("class/accessRequests/5:approve",
                "AccessRequestService/ApproveAccessRequest", "POST")
            if (transport != "rest") {
                assertEquals(Role.ROLE_UNSPECIFIED,
                    ApproveAccessRequestRequest.parseFrom(fixture.managementBytes(sent)).role)
            }
            fixture.success("""{"requestId":5,"who":" Person "}""",
                DeclineAccessRequestResponse.newBuilder().setRequestId(5).setWho(" Person ").build())
            val declined = repository.declineRequest(5).getOrNull()
            assertEquals(false, declined?.approved)
            assertNull(declined?.role)
            assertEquals("Person", declined?.who)
            fixture.managementRequest("class/accessRequests/5:decline",
                "AccessRequestService/DeclineAccessRequest", "POST")
        }
    }

    @Test
    fun `statistics map role-count rows without inventing an unknown role bar`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"stats":{"today":"2026-11-13","membersByRole":[
                {"role":"ROLE_EDITOR","count":4},{"role":99,"count":7}],"substitutionsUpcoming":5}}""",
                GetClassStatsResponse.newBuilder().setStats(ClassStats.newBuilder().setToday("2026-11-13")
                    .addMembersByRole(RoleCount.newBuilder().setRole(Role.ROLE_EDITOR).setCount(4))
                    .addMembersByRole(RoleCount.newBuilder().setRoleValue(99).setCount(7))
                    .setSubstitutionsUpcoming(5)).build())
            val stats = fixture.management().stats().getOrNull()
            assertEquals(mapOf(ClassRole.EDITOR to 4), stats?.membersByRole)
            assertEquals(5, stats?.overridesUpcoming)
            fixture.managementRequest("class/stats", "ClassService/GetClassStats")
        }
    }

    @Test
    fun `audit maps UTC instants in the fetched class zone and retains its opaque next token`(): Unit = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.classZone()
            fixture.success("""{"auditEntries":[{"id":9,"action":"future.action","summary":"Entry",
                "at":"2026-03-29T01:30:00Z"}],"nextPageToken":"opaque/+?=next"}""",
                ListAuditEntriesResponse.newBuilder().addAuditEntries(AuditEntry.newBuilder().setId(9)
                    .setAction("future.action").setSummary("Entry").setAt(stamp()))
                    .setNextPageToken("opaque/+?=next").build())
            val page = fixture.management().log().getOrNull()
            assertEquals(1, page?.entries?.size)
            assertEquals(LocalDateTime.parse("2026-03-29T03:30:00"), page?.entries?.single()?.at)
            assertEquals("opaque/+?=next", nextToken(page))
            fixture.managementRequest("class", "ClassService/GetClass")
            val sent = fixture.managementRequest("class/auditEntries", "AuditService/ListAuditEntries")
            if (transport == "rest") assertEquals("30", sent.url.queryParameter("pageSize")) else {
                assertEquals(30, ListAuditEntriesRequest.parseFrom(fixture.managementBytes(sent)).pageSize)
            }
            fixture.classZone()
            fixture.success("{}", ListAuditEntriesResponse.getDefaultInstance())
            assertEquals(emptyList<Any>(), fixture.management().log(pageToken = "opaque/+?=next").getOrNull()?.entries)
            fixture.managementRequest("class", "ClassService/GetClass")
            val next = fixture.managementRequest("class/auditEntries", "AuditService/ListAuditEntries")
            if (transport == "rest") assertEquals("opaque/+?=next", next.url.queryParameter("pageToken")) else {
                assertEquals("opaque/+?=next",
                    ListAuditEntriesRequest.parseFrom(fixture.managementBytes(next)).pageToken)
            }
        }
    }

    // This boundary projection reads the new domain token directly after the observed offset-only RED.
    private fun nextToken(page: AuditPage?): String? = page?.nextPageToken

    private fun stamp(): Timestamp = Timestamp.newBuilder()
        .setSeconds(Instant.parse("2026-03-29T01:30:00Z").epochSecond).build()

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

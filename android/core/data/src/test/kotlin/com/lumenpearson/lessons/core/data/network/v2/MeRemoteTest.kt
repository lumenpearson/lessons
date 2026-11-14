package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.CreateLinkCodeResponse
import com.lumenpearson.lessons.contract.v2.GetMeResponse
import com.lumenpearson.lessons.contract.v2.LinkCode
import com.lumenpearson.lessons.contract.v2.Me
import com.lumenpearson.lessons.contract.v2.Role
import com.lumenpearson.lessons.contract.v2.UnlinkMeResponse
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepositoryImpl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MeRemoteTest(private val transport: String) {
    @Test
    fun `linked refresh uses GetMe without minting a link code`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success(
                """{"me":{"deviceName":" phone ","linked":true,"role":"ROLE_ADMIN","canEdit":true}}""",
                GetMeResponse.newBuilder().setMe(Me.newBuilder().setDeviceName(" phone ")
                    .setLinked(true).setRole(Role.ROLE_ADMIN).setCanEdit(true)).build(),
            )
            assertEquals(DeviceLink("phone", true, ClassRole.ADMIN, true, null, null),
                repository(fixture).refresh().getOrNull())
            assertCalls(fixture, listOf("GET" to "me"), listOf("GetMe"))
        }
    }

    @Test
    fun `unlinked refresh explicitly creates a code and keeps the original Me fields`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"me":{"deviceName":" phone "}}""",
                GetMeResponse.newBuilder().setMe(Me.newBuilder().setDeviceName(" phone ")).build())
            fixture.success("""{"linkCode":{"code":" CODE23 ",
                "botDeepLink":" https://t.me/example?start=link_CODE23 "}}""",
                CreateLinkCodeResponse.newBuilder().setLinkCode(LinkCode.newBuilder().setCode(" CODE23 ")
                    .setBotDeepLink(" https://t.me/example?start=link_CODE23 ")).build())
            assertEquals(DeviceLink("phone", false, null, false, "CODE23", "https://t.me/example?start=link_CODE23"),
                repository(fixture).refresh().getOrNull())
            assertCalls(fixture, listOf("GET" to "me", "POST" to "me/linkCodes"),
                listOf("GetMe", "CreateLinkCode"))
        }
    }

    @Test
    fun `unlink uses its returned Me and obtains the replacement code explicitly`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"me":{"deviceName":"phone"}}""",
                UnlinkMeResponse.newBuilder().setMe(Me.newBuilder().setDeviceName("phone")).build())
            fixture.success("""{"linkCode":{"code":"NEW23"}}""",
                CreateLinkCodeResponse.newBuilder().setLinkCode(LinkCode.newBuilder().setCode("NEW23")).build())
            assertEquals(DeviceLink("phone", false, null, false, "NEW23", null),
                repository(fixture).unlink().getOrNull())
            assertCalls(fixture, listOf("POST" to "me:unlink", "POST" to "me/linkCodes"),
                listOf("UnlinkMe", "CreateLinkCode"))
        }
    }

    @Test
    fun `a future role keeps the linked phone readable without guessing edit rights`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"me":{"deviceName":" ","linked":true,"role":99}}""",
                GetMeResponse.newBuilder().setMe(Me.newBuilder().setDeviceName(" ")
                    .setLinked(true).setRoleValue(99)).build())
            assertEquals(DeviceLink(null, true, null, false, null, null),
                repository(fixture).refresh().getOrNull())
            assertCalls(fixture, listOf("GET" to "me"), listOf("GetMe"))
        }
    }

    @Test
    fun `missing Me is an answered protocol failure rather than an unlinked success`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("{}", GetMeResponse.getDefaultInstance())
            val failure = repository(fixture).refresh().exceptionOrNull()
            assertTrue(failure is RemoteError)
            assertEquals(200, (failure as RemoteError).httpStatus)
        }
    }

    @Test
    fun `a replacement bearer between GetMe and CreateLinkCode never receives the second call`() =
        assertIdentityChange { it.credentials = it.credentials.copy(classToken = "synthetic-replacement") }

    @Test
    fun `a changed server between GetMe and CreateLinkCode never receives the second call`() =
        assertIdentityChange { it.credentials = it.credentials.copy(baseUrl = "https://other.invalid/") }

    private fun assertIdentityChange(change: (V2BindingFixture) -> Unit) = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"me":{"deviceName":"A"}}""",
                GetMeResponse.newBuilder().setMe(Me.newBuilder().setDeviceName("A")).build())
            fixture.afterResponse = { change(fixture) }
            val repository = repository(fixture)
            assertTrue(repository.refresh().exceptionOrNull() is IOException)
            assertEquals(null, repository.link.value)
            assertEquals(1, fixture.server.requestCount)
            val request = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals(if (transport == "rest") "/school/api/v2/me"
                else "/school/api/rpc/lessons.v2.MeService/GetMe", request.url.encodedPath)
        }
    }

    private fun repository(fixture: V2BindingFixture) = DeviceLinkRepositoryImpl(
        remote = fixture.me,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun assertCalls(
        fixture: V2BindingFixture,
        rest: List<Pair<String, String>>,
        rpc: List<String>,
    ) {
        assertEquals(rest.size, fixture.server.requestCount)
        rest.indices.forEach { index ->
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-device", sent.headers["Authorization"])
            assertEquals("73", sent.headers["X-Lessons-Client"])
            assertEquals(if (transport == "rest") rest[index].first else "POST", sent.method)
            assertEquals(if (transport == "rest") "/school/api/v2/${rest[index].second}"
                else "/school/api/rpc/lessons.v2.MeService/${rpc[index]}", sent.url.encodedPath)
            if (transport == "rest" && rest[index].first == "POST") assertEquals("{}", sent.body?.utf8())
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

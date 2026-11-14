package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.CreateDiarySessionRequest
import com.lumenpearson.lessons.contract.v2.CreateDiarySessionResponse
import com.lumenpearson.lessons.contract.v2.DeleteDiarySessionResponse
import com.lumenpearson.lessons.contract.v2.DiaryCapabilities
import com.lumenpearson.lessons.contract.v2.DiarySession as ProtoSession
import com.lumenpearson.lessons.contract.v2.DiaryStudent as ProtoStudent
import com.lumenpearson.lessons.contract.v2.GetDiaryCapabilitiesResponse
import com.lumenpearson.lessons.contract.v2.ProviderCapabilities
import com.lumenpearson.lessons.core.data.diary.MemoryDiaryStore
import com.lumenpearson.lessons.core.data.diary.UnusedSignIn
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySignInImpl
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.upstream.FakeUpstreamDirectory
import com.lumenpearson.lessons.core.data.upstream.PETERSBURG_JWT
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
import com.lumenpearson.lessons.core.data.upstream.originOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DiarySessionRemoteTest(private val transport: String) {
    @Test
    fun `v2 preflight uses its provider list without the removed registration flag`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"capabilities":{"enabled":true,
                "providers":[{"provider":"petersburg"}]}}""",
                GetDiaryCapabilitiesResponse.newBuilder().setCapabilities(DiaryCapabilities.newBuilder()
                    .setEnabled(true)
                    .addProviders(ProviderCapabilities.newBuilder().setProvider("petersburg"))).build())
            assertTrue(signIn(fixture, MemoryDiaryStore()).preflight(DiaryTarget.petersburg("parent")).isSuccess)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertNull(sent.headers["Authorization"])
            assertEquals(if (transport == "rest") "/school/api/v2/diary/capabilities"
                else "/school/api/rpc/lessons.v2.DiaryService/GetDiaryCapabilities", sent.url.encodedPath)
        }
    }

    @Test
    fun `disabled v2 capabilities keep their specific preflight outcome`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("""{"capabilities":{}}""", GetDiaryCapabilitiesResponse.newBuilder()
                .setCapabilities(DiaryCapabilities.getDefaultInstance()).build())
            assertEquals(DiarySignInProblem.ServerDisabled,
                signIn(fixture, MemoryDiaryStore()).preflight(DiaryTarget.petersburg("parent")).exceptionOrNull())
        }
    }

    @Test
    fun `registration sends only the upstream session and stores the returned diary identity`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            val store = MemoryDiaryStore(session = null)
            val upstream = UpstreamSession.Petersburg(DiaryTarget.petersburg("parent"),
                PETERSBURG_JWT, fixture.clock.instant())
            fixture.success("""{"session":{"token":"synthetic-issued","login":"server-login",
                "provider":"petersburg","zone":"Asia/Chita","students":[
                {"id":"7","firstName":"Ivan","lastName":"Petrov"}]}}""",
                CreateDiarySessionResponse.newBuilder().setSession(ProtoSession.newBuilder()
                    .setToken("synthetic-issued").setLogin("server-login").setProvider("petersburg")
                    .setZone("Asia/Chita").addStudents(ProtoStudent.newBuilder().setId(7)
                        .setFirstName("Ivan").setLastName("Petrov"))).build())
            val expected = DiarySession("server-login", "synthetic-issued",
                DiaryTarget.petersburg("server-login").copy(zone = "Asia/Chita"))
            val registered = signIn(fixture, store).register(upstream).getOrNull()
            assertEquals(expected, registered?.session)
            assertEquals(listOf(DiaryStudent(7, "Ivan", "Petrov", null, "Petrov Ivan", null, null)),
                registered?.students)
            assertEquals(expected, store.session)
            assertTrue(upstream.spent)
            assertEquals(1, fixture.server.requestCount)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertNull(sent.headers["Authorization"])
            assertEquals("POST", sent.method)
            if (transport == "rest") {
                assertEquals("/school/api/v2/diary/sessions", sent.url.encodedPath)
                val body = NetworkModule.json().parseToJsonElement(checkNotNull(sent.body).utf8()).jsonObject
                assertEquals(setOf("login", "petersburg"), body.keys)
                assertEquals("parent", body.getValue("login").jsonPrimitive.content)
                assertEquals(PETERSBURG_JWT,
                    body.getValue("petersburg").jsonObject.getValue("token").jsonPrimitive.content)
            } else {
                assertEquals("/school/api/rpc/lessons.v2.DiaryService/CreateDiarySession", sent.url.encodedPath)
                val bytes = checkNotNull(sent.body).toByteArray().let {
                    if (transport == "grpc") it.drop(5).toByteArray() else it
                }
                val body = CreateDiarySessionRequest.parseFrom(bytes)
                assertEquals("parent", body.login)
                assertEquals(PETERSBURG_JWT, body.petersburg.token)
                assertEquals(CreateDiarySessionRequest.CredentialCase.PETERSBURG, body.credentialCase)
            }
        }
    }

    @Test
    fun `sign out deletes only the current diary session over its own bearer`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success("{}", DeleteDiarySessionResponse.getDefaultInstance())
            val store = MemoryDiaryStore()
            val repository = DiaryRepositoryImpl(api(fixture), store, UnusedSignIn,
                ioDispatcher = Dispatchers.Unconfined)
            assertTrue(repository.signOut().isSuccess)
            assertNull(store.session)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("Bearer synthetic-diary", sent.headers["Authorization"])
            assertEquals(if (transport == "rest") "DELETE" else "POST", sent.method)
            assertEquals(if (transport == "rest") "/school/api/v2/diary/sessions/current"
                else "/school/api/rpc/lessons.v2.DiaryService/DeleteDiarySession", sent.url.encodedPath)
        }
    }

    private fun api(fixture: V2BindingFixture): DiaryRemote =
        fixture.diary

    private fun signIn(fixture: V2BindingFixture, store: MemoryDiaryStore): DiarySignInImpl = DiarySignInImpl(
        api = api(fixture), store = store,
        directory = { FakeUpstreamDirectory(originOf(fixture.server)) },
        client = { error("Preflight and a Petersburg handover must not call the upstream") },
        clock = fixture.clock, ioDispatcher = Dispatchers.Unconfined,
    )

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

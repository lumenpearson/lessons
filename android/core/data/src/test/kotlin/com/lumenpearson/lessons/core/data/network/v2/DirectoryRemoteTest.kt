package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.Any as ProtoAny
import com.google.rpc.ErrorInfo
import com.google.rpc.Status
import com.lumenpearson.lessons.contract.v2.ListSchoolRegionsRequest
import com.lumenpearson.lessons.contract.v2.ListSchoolRegionsResponse
import com.lumenpearson.lessons.contract.v2.SchoolRegion
import com.lumenpearson.lessons.core.data.catalog.DirectoryProblem
import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.catalog.RegionSearch
import com.lumenpearson.lessons.core.data.catalog.ServerSchoolDirectory
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.core.data.upstream.DiarySchoolSearch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.Headers
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DirectoryRemoteTest(private val transport: String) {
    private val catalog by lazy {
        RegionCatalog.parse(checkNotNull(javaClass.classLoader?.getResourceAsStream(RegionCatalog.ASSET))
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @Test
    fun `anonymous v2 lookup preserves unplaced regions and sends the normalized query`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            fixture.success(
                """{"query":"лицей 1535","regions":[{"region":"moscow","code":"77","schools":3},
                    {"region":"future-region","code":"99","schools":1}],"truncated":true}""",
                ListSchoolRegionsResponse.newBuilder().setQuery("лицей 1535").setTruncated(true)
                    .addRegions(SchoolRegion.newBuilder().setRegion("moscow").setCode("77").setSchools(3))
                    .addRegions(SchoolRegion.newBuilder().setRegion("future-region").setCode("99").setSchools(1))
                    .build(),
            )
            val result = directory(fixture).regionsForSchool("  лицей   1535 ").getOrNull()
            assertEquals("лицей 1535", result?.query)
            assertEquals(listOf("moscow", null), result?.hits?.map { it.region?.key })
            assertEquals("future-region", result?.hits?.getOrNull(1)?.label)
            assertTrue(result?.truncated == true)
            val sent = checkNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertNull(sent.headers["Authorization"])
            assertEquals("73", sent.headers["X-Lessons-Client"])
            if (transport == "rest") {
                assertEquals("/school/api/v2/schoolRegions", sent.url.encodedPath)
                assertEquals("лицей 1535", sent.url.queryParameter("query"))
                assertNull(sent.url.queryParameter("q"))
            } else {
                assertEquals("/school/api/rpc/lessons.v2.DirectoryService/ListSchoolRegions", sent.url.encodedPath)
                val bytes = checkNotNull(sent.body).toByteArray().let {
                    if (transport == "grpc") it.drop(5).toByteArray() else it
                }
                assertEquals("лицей 1535", ListSchoolRegionsRequest.parseFrom(bytes).query)
            }
        }
    }

    @Test
    fun `daily directory allowance is distinct from caller throttling over every binding`() = runBlocking {
        V2BindingFixture(transport).use { fixture ->
            enqueueSpent(fixture)
            assertEquals(DirectoryProblem.Spent(7),
                directory(fixture).regionsForSchool("лицей 1535").exceptionOrNull())
        }
    }

    private fun directory(fixture: V2BindingFixture) = ServerSchoolDirectory(
        api = fixture.directory,
        search = { RegionSearch(catalog) },
        schools = object : DiarySchoolSearch {
            override suspend fun search(regionKey: String, query: String): Result<List<DiarySchool>> =
                error("This lookup must not ask an upstream diary")
        },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun enqueueSpent(fixture: V2BindingFixture) {
        val info = ErrorInfo.newBuilder().setReason("DIRECTORY_SPENT")
            .putMetadata("retry_after_seconds", "7").build()
        when (transport) {
            "rest" -> fixture.server.enqueue(MockResponse.Builder().code(429)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"status":"RESOURCE_EXHAUSTED","details":[
                    {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"DIRECTORY_SPENT",
                    "metadata":{"retry_after_seconds":"7"}}]}}""").build())
            "connect" -> fixture.server.enqueue(MockResponse.Builder().code(429)
                .addHeader("Content-Type", "application/json")
                .body("""{"code":"resource_exhausted","message":"synthetic","details":[
                    {"type":"google.rpc.ErrorInfo","value":"${info.toByteArray().toByteString().base64()}"}]}""")
                .build())
            else -> {
                val status = Status.newBuilder().setCode(8).addDetails(ProtoAny.newBuilder()
                    .setTypeUrl("type.googleapis.com/google.rpc.ErrorInfo").setValue(info.toByteString())).build()
                fixture.server.enqueue(MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/grpc+proto")
                    .body(Buffer()).trailers(Headers.Builder().add("grpc-status", "8")
                        .add("grpc-status-details-bin", status.toByteArray().toByteString().base64()).build()).build())
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

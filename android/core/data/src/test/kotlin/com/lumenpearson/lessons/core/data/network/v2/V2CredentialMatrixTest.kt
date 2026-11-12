package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import retrofit2.Call
import retrofit2.Invocation
import retrofit2.http.GET

@RunWith(Parameterized::class)
class V2CredentialMatrixTest(private val path: String, private val methodName: String?, private val kind: String) {
    @Test
    fun `only the declared credential reaches the server for every stored token combination`() {
        MockWebServer().use { server ->
            server.start()
            var current = RequestCredentials(server.url("/school/").toString(), null, null)
            val client = NetworkModule.okHttpClient({ current }, CleartextPolicy { true })
            val combinations = listOf(null to null, "class" to null, null to "diary", "class" to "diary", " " to " ")
            for ((device, diary) in combinations) {
                current = current.copy(classToken = device, diaryToken = diary)
                server.enqueue(MockResponse())
                val request = Request.Builder().url("http://base-url.invalid$path")
                    .header("Authorization", "Bearer stale")
                if (methodName != null) {
                    request.tag(Invocation::class.java, Invocation.of(
                        Endpoints::class.java.getDeclaredMethod(methodName), emptyList<Any>(),
                    ))
                }
                client.newCall(request.build()).execute().close()
                val expected = when (kind) {
                    "device" -> device
                    "diary" -> diary
                    else -> null
                }?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

                assertEquals("$path with device=$device diary=$diary", expected,
                    server.takeRequest().headers["Authorization"])
            }
        }
    }

    private interface Endpoints {
        @GET("api/v2/class/scheduleWindows/2026")
        @Credential(CredentialKind.DEVICE)
        fun device(): Call<ResponseBody>

        @Credential(CredentialKind.DIARY)
        fun diary()

        @Credential(CredentialKind.NONE)
        fun createDevice()

        @Credential(CredentialKind.NONE)
        fun capabilities()

        @Credential(CredentialKind.NONE)
        fun createSession()

        @Credential(CredentialKind.NONE)
        fun regions()

        fun unannotated()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} [{1}]")
        fun methods() = listOf(
            arrayOf("/api/v2/devices", "createDevice", "none"),
            arrayOf("/api/v2/diary/capabilities", "capabilities", "none"),
            arrayOf("/api/v2/diary/sessions", "createSession", "none"),
            arrayOf("/api/v2/schoolRegions", "regions", "none"),
            arrayOf("/api/v2/class/scheduleWindows/2026", "device", "device"),
            arrayOf("/api/v2/diary/students", "diary", "diary"),
            arrayOf("/api/v2/unknown", "unannotated", "none"),
            arrayOf("/api/v2/unknown", null, "none"),
            arrayOf("/api/rpc/lessons.v2.ScheduleService/GetScheduleWindow", "diary", "device"),
            arrayOf("/api/rpc/lessons.v2.DiaryService/ListStudents", null, "diary"),
            arrayOf("/api/rpc/lessons.v2.DeviceService/Missing", null, "none"),
            arrayOf("/api/rpc/lessons.v2.DeviceService/createDevice", null, "none"),
            arrayOf("/api/rpc/lessons.v2.DeviceService/CreateDevice", null, "none"),
            arrayOf("/api/rpc/lessons.v2.DiaryService/GetDiaryCapabilities", null, "none"),
            arrayOf("/api/rpc/lessons.v2.DiaryService/CreateDiarySession", null, "none"),
            arrayOf("/api/rpc/lessons.v2.DirectoryService/ListSchoolRegions", null, "none"),
        )
    }
}

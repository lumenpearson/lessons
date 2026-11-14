package com.lumenpearson.lessons.core.data.network.v2

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.google.protobuf.Any as ProtoAny
import com.google.rpc.ErrorInfo
import com.google.rpc.Status
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.diary.UnusedSignIn
import com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.Session
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import mockwebserver3.MockResponse
import okhttp3.Headers
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals

internal class DiaryTokenFixture(private val transport: String) : AutoCloseable {
    val binding = V2BindingFixture(transport)
    private val underlying = MemoryStore()
    val store = DiaryWriteGate(underlying)
    private val vault = testVault()
    val preferences = LessonsPreferences(store, vault, Dispatchers.Unconfined)
    val session = DiarySession("synthetic-parent", "synthetic-diary-A")

    suspend fun seed() {
        preferences.updateSettings { it.copy(baseUrl = binding.server.url("/school/").toString()) }
        preferences.addSession(Session(7, "Synthetic class", null, "synthetic-class"))
        preferences.writeDiarySession(session)
        preferences.selectStudent(17)
        binding.credentials = preferences.credentials.refresh()
    }

    fun otherPreferences() = LessonsPreferences(underlying, vault, Dispatchers.Unconfined)

    fun repository(remote: DiaryRemote = binding.diary) = DiaryRepositoryImpl(
        remote, preferences, UnusedSignIn, ioDispatcher = Dispatchers.Unconfined,
    )

    fun refuse(reason: String = "DIARY_TOKEN_INVALID", authentication: Boolean = true) {
        val code = if (authentication) "UNAUTHENTICATED" else "UNAVAILABLE"
        val http = if (authentication) 401 else 503
        val grpc = if (authentication) 16 else 14
        val info = ErrorInfo.newBuilder().setReason(reason).setDomain("lessons.app").build()
        when (transport) {
            "rest" -> binding.server.enqueue(MockResponse.Builder().code(http)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"status":"$code","details":[
                    {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"$reason",
                    "domain":"lessons.app"}]}}""").build())
            "connect" -> binding.server.enqueue(MockResponse.Builder().code(http)
                .addHeader("Content-Type", "application/json")
                .body("""{"code":"${code.lowercase()}","message":"synthetic","details":[
                    {"type":"google.rpc.ErrorInfo","value":"${info.toByteArray().toByteString().base64()}"}]}""")
                .build())
            else -> {
                val status = Status.newBuilder().setCode(grpc).addDetails(ProtoAny.newBuilder()
                    .setTypeUrl("type.googleapis.com/google.rpc.ErrorInfo").setValue(info.toByteString())).build()
                binding.server.enqueue(MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/grpc+proto")
                    .body(Buffer()).trailers(Headers.Builder().add("grpc-status", grpc.toString())
                        .add("grpc-status-details-bin", status.toByteArray().toByteString().base64()).build()).build())
            }
        }
    }

    fun assertRequest() {
        val sent = checkNotNull(binding.server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("Bearer synthetic-diary-A", sent.headers["Authorization"])
        assertEquals("73", sent.headers["X-Lessons-Client"])
        assertEquals(if (transport == "rest") "/school/api/v2/diary/students"
            else "/school/api/rpc/lessons.v2.DiaryService/ListStudents", sent.url.encodedPath)
        assertEquals(1, binding.server.requestCount)
    }

    override fun close() = binding.close()
}

internal class DiaryWriteGate(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    var beforeWrite: suspend () -> Unit = {}
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        beforeWrite()
        return delegate.updateData(transform)
    }
}

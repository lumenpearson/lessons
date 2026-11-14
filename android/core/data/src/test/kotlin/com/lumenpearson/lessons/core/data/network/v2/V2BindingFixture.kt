package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.MessageLite
import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.Protocol
import okio.Buffer

internal class V2BindingFixture(val transport: String) : AutoCloseable {
    val server = MockWebServer()
    val clock: Clock = Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneOffset.UTC)
    val client: okhttp3.OkHttpClient
    val join: JoinRemote
    val schedule: ScheduleRemote
    val manage: ManageRemote
    val diary: DiaryRemote
    val directory: DirectoryRemote
    val me: MeRemote
    @Volatile
    var credentials: RequestCredentials
    var afterResponse: (() -> Unit)? = null

    init {
        if (transport == "grpc") server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.start()
        credentials = RequestCredentials(server.url("/school/").toString(), "synthetic-device", "synthetic-diary")
        client = NetworkModule.okHttpClient({ credentials }, CleartextPolicy { true }, clientVersion = 73)
            .newBuilder().apply {
                addInterceptor { chain -> chain.proceed(chain.request()).also { afterResponse?.invoke() } }
                if (transport == "grpc") protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            }.build()
        val remotes = V2Remotes.using(client, NetworkModule.retrofit(client, NetworkModule.json()),
            AppTransport.parse(transport), clock)
        join = remotes.join
        schedule = remotes.schedule
        me = remotes.me
        manage = remotes.manage
        diary = remotes.diary
        directory = remotes.directory
    }

    fun success(json: String, message: MessageLite, etag: String? = null) {
        val response = MockResponse.Builder().code(200)
        when (transport) {
            "rest" -> response.addHeader("Content-Type", "application/json").body(json)
            "connect" -> response.addHeader("Content-Type", "application/proto")
                .body(Buffer().write(message.toByteArray()))
            else -> response.addHeader("Content-Type", "application/grpc+proto")
                .body(Buffer().writeByte(0).writeInt(message.serializedSize).write(message.toByteArray()))
                .trailers(Headers.Builder().add("grpc-status", "0").build())
        }
        if (etag != null) response.addHeader("ETag", etag)
        server.enqueue(response.build())
    }

    override fun close() {
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

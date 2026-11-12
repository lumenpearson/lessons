package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.network.ServerNeedsHttpsException
import java.net.SocketTimeoutException
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class V2NoAnswerTest(private val transport: String) {
    @Test
    fun `both remotes preserve missing address HTTPS refusal and the original timeout`() = runBlocking {
        for (address in listOf("", "http://school.invalid/", "https://school.invalid/")) {
            val original = SocketTimeoutException("synthetic timeout")
            val credentials = RequestCredentials(address, null, null)
            val client = NetworkModule.okHttpClient({ credentials }, CleartextPolicy { false }, clientVersion = 73)
                .newBuilder().addInterceptor { throw original }.build()
            val remotes = V2Remotes.using(client, NetworkModule.retrofit(client, NetworkModule.json()),
                AppTransport.parse(transport), Clock.systemUTC())
            val join = runCatching { remotes.join.create("DEMO24", null) }.exceptionOrNull()
            val schedule = runCatching { remotes.schedule.window(2026, null) }.exceptionOrNull()
            for (actual in listOf(join, schedule)) {
                when (address) {
                    "" -> assertTrue(actual is ServerAddressMissingException)
                    "http://school.invalid/" -> assertTrue(actual is ServerNeedsHttpsException)
                    else -> assertSame(original, actual)
                }
            }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports() = listOf(arrayOf("rest"), arrayOf("connect"), arrayOf("grpc"))
    }
}

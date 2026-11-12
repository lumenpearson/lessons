package com.lumenpearson.lessons.core.data.network.v2

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ObservedRpcClientTest {
    @Test
    fun `per call tagging views retain the one server pool dispatcher and timeout budgets`() {
        val base = OkHttpClient.Builder().build()
        val first = base.observing(CallObservation())
        val second = base.observing(CallObservation())
        for (view in listOf(first, second)) {
            assertSame(base.connectionPool, view.connectionPool)
            assertSame(base.dispatcher, view.dispatcher)
            assertSame(base.socketFactory, view.socketFactory)
            assertEquals(base.connectTimeoutMillis, view.connectTimeoutMillis)
            assertEquals(base.readTimeoutMillis, view.readTimeoutMillis)
            assertEquals(base.writeTimeoutMillis, view.writeTimeoutMillis)
            assertEquals(base.callTimeoutMillis, view.callTimeoutMillis)
            assertEquals(base.interceptors.size + 1, view.interceptors.size)
        }
    }
}

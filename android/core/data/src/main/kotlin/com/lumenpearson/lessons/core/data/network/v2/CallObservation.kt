package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.RequestCredentials
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** Owned by one call; callback and coroutine threads never share it with another request. */
internal class CallObservation(private val expected: RequestCredentials? = null) {
    @Volatile
    var credentials: RequestCredentials? = null
        private set

    @Volatile
    var status: Int? = null

    @Volatile
    var failure: Throwable? = null

    fun recordRequest(actual: RequestCredentials) {
        if (expected != null &&
            (actual.baseUrl != expected.baseUrl || actual.classToken != expected.classToken)
        ) {
            throw IOException("The membership changed before the schedule request was sent")
        }
        credentials = actual
    }
}

/** The official RPC adapter accepts a client, so the tagging view shares all expensive resources. */
internal fun OkHttpClient.observing(observation: CallObservation): OkHttpClient = newBuilder().apply {
    interceptors().add(0, Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().tag(CallObservation::class.java, observation).build())
    })
}.build()

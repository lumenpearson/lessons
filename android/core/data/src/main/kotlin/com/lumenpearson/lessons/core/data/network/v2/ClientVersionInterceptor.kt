package com.lumenpearson.lessons.core.data.network.v2

import okhttp3.Interceptor
import okhttp3.Response

internal class ClientVersionInterceptor(private val clientVersion: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header("X-Lessons-Client", clientVersion.toString()).build(),
    )
}

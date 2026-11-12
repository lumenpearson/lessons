package com.lumenpearson.lessons.core.data.network.v2

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal class RecordCallStatusInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // Retrofit may fail while buffering an error body, before returning its Response.
        val observation = chain.request().tag(CallObservation::class.java) ?: return response
        observation.status = response.code
        val body = response.body
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                super.read(sink, byteCount)
            } catch (failure: IOException) {
                observation.failure = failure
                throw failure
            }
        }.buffer()
        val observed = object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }
        return response.newBuilder().body(observed).build()
    }
}

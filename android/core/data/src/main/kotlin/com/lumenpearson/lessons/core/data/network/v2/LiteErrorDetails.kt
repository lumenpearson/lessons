package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.AnyError
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ErrorDetailParser
import com.google.protobuf.MessageLite
import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import com.google.rpc.RetryInfo
import com.google.rpc.Status
import kotlin.reflect.KClass
import okio.ByteString.Companion.toByteString

internal object LiteErrorDetails : ErrorDetailParser {
    // Wire names cannot be inferred from Java class names after R8 has renamed them.
    private val types: Map<Class<*>, Pair<String, MessageLite>> = mapOf(
        ErrorInfo::class.java to ("google.rpc.ErrorInfo" to ErrorInfo.getDefaultInstance()),
        BadRequest::class.java to ("google.rpc.BadRequest" to BadRequest.getDefaultInstance()),
        RetryInfo::class.java to ("google.rpc.RetryInfo" to RetryInfo.getDefaultInstance()),
    )

    override fun <E : Any> unpack(any: AnyError, clazz: KClass<E>): E? {
        val (name, instance: MessageLite) = types[clazz.java] ?: return null
        return if (any.typeUrl.substringAfterLast('/') == name) {
            clazz.java.cast(instance.parserForType.parseFrom(any.value.toByteArray()))
        } else {
            null
        }
    }

    override fun parseDetails(bytes: ByteArray): List<ConnectErrorDetail> =
        Status.parseFrom(bytes).detailsList.map {
            ConnectErrorDetail(type = it.typeUrl, payload = it.value.toByteArray().toByteString())
        }
}

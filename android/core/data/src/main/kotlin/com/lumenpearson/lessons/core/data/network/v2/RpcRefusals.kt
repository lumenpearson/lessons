package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.ConnectException
import com.google.protobuf.InvalidProtocolBufferException
import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import com.google.rpc.RetryInfo
import java.time.Duration

internal object RpcRefusals {
    fun decode(error: ConnectException, status: Int?): RemoteError {
        val info = details<ErrorInfo>(error).firstOrNull()
        val violations = details<BadRequest>(error).flatMap { bad ->
            bad.fieldViolationsList.map { RemoteViolation(it.field, it.description) }
        }
        val retry = details<RetryInfo>(error).firstOrNull()?.retryDelay
        return RemoteError(
            code = error.code,
            reason = info?.reason?.takeIf { it.isNotEmpty() },
            metadata = info?.metadataMap?.toMap().orEmpty(),
            message = error.message ?: "RPC ${error.code.codeName}",
            httpStatus = status,
            violations = violations,
            retryDelay = retry?.let { Duration.ofSeconds(it.seconds, it.nanos.toLong()) },
            cause = error.exception ?: error,
        )
    }

    private inline fun <reified T : Any> details(error: ConnectException): List<T> =
        error.details.mapNotNull {
            try {
                LiteErrorDetails.unpack(it.pb, T::class)
            } catch (_: InvalidProtocolBufferException) {
                null
            }
        }
}

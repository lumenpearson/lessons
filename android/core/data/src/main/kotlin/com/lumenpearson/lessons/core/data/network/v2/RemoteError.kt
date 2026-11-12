package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import java.time.Duration

internal data class RemoteViolation(val field: String, val description: String)

internal class RemoteError(
    val code: Code,
    val reason: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    message: String = "The server refused the request",
    val httpStatus: Int? = null,
    val violations: List<RemoteViolation> = emptyList(),
    val retryDelay: Duration? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    init {
        ClientCompatibility.record(reason)
    }
}

@Suppress("MagicNumber") // HTTP status numbers are protocol identifiers.
internal fun canonicalHttpCode(status: Int): Code = when (status) {
    400 -> Code.INVALID_ARGUMENT
    401 -> Code.UNAUTHENTICATED
    403 -> Code.PERMISSION_DENIED
    404 -> Code.NOT_FOUND
    409 -> Code.ABORTED
    429 -> Code.RESOURCE_EXHAUSTED
    499 -> Code.CANCELED
    500 -> Code.INTERNAL_ERROR
    501 -> Code.UNIMPLEMENTED
    503 -> Code.UNAVAILABLE
    504 -> Code.DEADLINE_EXCEEDED
    else -> Code.UNKNOWN
}

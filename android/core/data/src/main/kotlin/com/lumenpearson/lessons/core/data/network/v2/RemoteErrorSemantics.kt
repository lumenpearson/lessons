package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code

internal fun RemoteError.retrySeconds(): Long? {
    val delay = retryDelay?.takeUnless { it.isNegative }
    return delay?.let {
        // A fractional wait must not enable another request before the refusal expires.
        if (it.nano > 0 && it.seconds < Long.MAX_VALUE) it.seconds + 1 else it.seconds
    } ?: metadata["retry_after_seconds"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
}

internal fun RemoteError.hasNoV2(): Boolean = reason.isNullOrBlank() &&
    (httpStatus == HTTP_NOT_FOUND || httpStatus == HTTP_NOT_IMPLEMENTED || code == Code.UNIMPLEMENTED)

/** gRPC may answer an error with HTTP200; its protocol code is then the useful fallback. */
@Suppress("MagicNumber") // HTTP status ranges are protocol identifiers.
internal fun RemoteError.failureStatus(): Int? =
    httpStatus?.takeUnless { it in 200..299 } ?: CodeStatuses[code] ?: httpStatus

@Suppress("MagicNumber") // The canonical status mapping, not application thresholds.
private val CodeStatuses = mapOf(
    Code.INVALID_ARGUMENT to 400,
    Code.FAILED_PRECONDITION to 400,
    Code.OUT_OF_RANGE to 400,
    Code.UNAUTHENTICATED to 401,
    Code.PERMISSION_DENIED to 403,
    Code.NOT_FOUND to 404,
    Code.ALREADY_EXISTS to 409,
    Code.ABORTED to 409,
    Code.RESOURCE_EXHAUSTED to 429,
    Code.CANCELED to 499,
    Code.UNIMPLEMENTED to 501,
    Code.UNAVAILABLE to 503,
    Code.DEADLINE_EXCEEDED to 504,
    Code.INTERNAL_ERROR to 500,
    Code.DATA_LOSS to 500,
    Code.UNKNOWN to 500,
)

private const val HTTP_NOT_FOUND = 404
private const val HTTP_NOT_IMPLEMENTED = 501

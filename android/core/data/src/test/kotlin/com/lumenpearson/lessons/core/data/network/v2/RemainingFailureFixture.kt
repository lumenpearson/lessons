package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import java.time.Duration

internal fun remainingRefusal(
    reason: String?,
    code: Code = Code.PERMISSION_DENIED,
    status: Int = 403,
    metadata: Map<String, String> = emptyMap(),
    message: String = "translated",
    delay: Duration? = null,
) = RemoteError(code, reason, metadata, message, status, retryDelay = delay)

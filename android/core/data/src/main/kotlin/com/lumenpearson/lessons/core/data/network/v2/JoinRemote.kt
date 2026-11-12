package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.Session

internal fun interface JoinRemote {
    suspend fun create(code: String, deviceName: String?): Session
}

package com.lumenpearson.lessons.core.data.network

import okhttp3.Request

/** The original API path survives a reverse-proxy prefix without another settings read. */
internal data class ServerRequestContext(val path: String, val credentials: RequestCredentials)

internal fun Request.serverPath(): String = tag(ServerRequestContext::class.java)?.path ?: url.encodedPath

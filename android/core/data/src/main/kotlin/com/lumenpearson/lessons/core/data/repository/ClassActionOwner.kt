package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.RequestCredentials

/** A queued action must retain the displayed identity before a dispatcher can delay it. */
data class ClassActionOwner(val classId: Long, val baseUrl: String, val token: String) {
    internal fun matches(currentClassId: Long?, credentials: RequestCredentials): Boolean =
        currentClassId == classId && credentials.baseUrl == baseUrl && credentials.classToken == token

    override fun toString(): String = "ClassActionOwner(classId=$classId, baseUrl=$baseUrl, token=<redacted>)"
}

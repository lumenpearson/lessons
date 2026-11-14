package com.lumenpearson.lessons.core.data.repository

internal data class DiaryCapabilities(val enabled: Boolean, val providers: Map<String, Set<String>>)

/** Short-lived upstream credentials never acquire a serializer or a value-printing toString. */
internal sealed class DiaryCredential {
    class Petersburg(val token: String) : DiaryCredential()
    class NetSchool(
        val at: String,
        val sessionCookie: String,
        val securityCookie: String?,
        val ver: String?,
        val timeOut: Int?,
    ) : DiaryCredential()

    final override fun toString(): String = "DiaryCredential(<redacted>)"
}

internal class DiaryHandover(val target: DiaryTarget, val credential: DiaryCredential) {
    override fun toString(): String = "DiaryHandover(${target.provider.wire}, <redacted>)"
}

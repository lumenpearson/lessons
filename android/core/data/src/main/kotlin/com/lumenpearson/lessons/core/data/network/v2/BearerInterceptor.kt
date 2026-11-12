package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.ServerRequestContext
import com.lumenpearson.lessons.core.data.network.serverPath
import com.lumenpearson.lessons.core.data.network.v2.dto.MethodCredentials
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Invocation

internal class BearerInterceptor(private val credentials: () -> RequestCredentials) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.serverPath()
        if (!path.startsWith("/api/rpc/") && !path.startsWith("/api/v2/")) return chain.proceed(request)
        val snapshot = request.tag(ServerRequestContext::class.java)?.credentials ?: credentials()
        val kind = if (path.startsWith("/api/rpc/")) {
            when (MethodCredentials.all[path.removePrefix("/api/rpc/")]) {
                "device" -> CredentialKind.DEVICE
                "diary" -> CredentialKind.DIARY
                else -> CredentialKind.NONE
            }
        } else {
            request.tag(Invocation::class.java)?.method()?.getAnnotation(Credential::class.java)?.kind
                ?: CredentialKind.NONE
        }
        val token = when (kind) {
            CredentialKind.DEVICE -> snapshot.classToken
            CredentialKind.DIARY -> snapshot.diaryToken
            CredentialKind.NONE -> null
        }?.takeIf { it.isNotBlank() }
        val signed = request.newBuilder().removeHeader("Authorization")
        if (token != null) signed.header("Authorization", "Bearer $token")
        return chain.proceed(signed.build())
    }
}

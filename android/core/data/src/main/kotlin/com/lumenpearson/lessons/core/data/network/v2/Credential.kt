package com.lumenpearson.lessons.core.data.network.v2

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class Credential(val kind: CredentialKind)

internal enum class CredentialKind { NONE, DEVICE, DIARY }

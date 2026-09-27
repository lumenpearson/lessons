package com.lumenpearson.lessons.core.data.network

/**
 * What the three interceptors put on a request: where it goes and which of the
 * two bearers it may carry.
 *
 * One value rather than three providers because all three come out of the same
 * preferences file and change on the same writes — a join, a switch, a
 * sign-in, a new address — and one value is one thing to keep current.
 *
 * @property baseUrl the server address as stored; blank while none is set,
 *   which [BaseUrlInterceptor] refuses rather than sends.
 * @property classToken the bearer of the class on screen, or `null` in none.
 * @property diaryToken the diary's own bearer, or `null` while nobody is signed
 *   in to one. Never a substitute for [classToken]; see [DiaryAuthInterceptor].
 */
internal data class RequestCredentials(
    val baseUrl: String,
    val classToken: String?,
    val diaryToken: String?,
) {
    /** The bearers are secrets, and a data class prints every field it has. */
    override fun toString(): String =
        "RequestCredentials(baseUrl=$baseUrl, classToken=${mask(classToken)}, diaryToken=${mask(diaryToken)})"

    private fun mask(token: String?): String = if (token == null) "null" else "<redacted>"
}

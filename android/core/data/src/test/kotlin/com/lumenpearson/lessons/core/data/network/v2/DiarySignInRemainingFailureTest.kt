package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import org.junit.Assert.assertEquals
import org.junit.Test

class DiarySignInRemainingFailureTest {
    @Test
    fun `sign in reasons keep password session and server failures distinct`() {
        val cases = listOf(
            "DIARY_CREDENTIALS_REJECTED" to DiarySignInProblem.WrongPassword(null),
            "DIARY_NO_STUDENTS" to DiarySignInProblem.NoStudent,
            "DIARY_DISABLED" to DiarySignInProblem.ServerDisabled,
            "DIARY_UNAVAILABLE" to DiarySignInProblem.ProviderUnavailable(null),
            "DIARY_UPSTREAM_UNREADABLE" to DiarySignInProblem.ProviderUnreadable(null),
            "DIARY_REAUTH" to DiarySignInProblem.ReauthRequired,
            "DIARY_TOKEN_INVALID" to DiarySignInProblem.SignInRequired,
        )
        for ((reason, want) in cases) assertEquals(reason, want, DiarySignInProblem.of(remainingRefusal(reason)))
        assertEquals(DiarySignInProblem.ServerAddressRefused, DiarySignInProblem.of(
            remainingRefusal("DIARY_UNAVAILABLE", metadata = mapOf("upstream" to "address-refused")),
        ))
        assertEquals(DiarySignInProblem.Timeout(null), DiarySignInProblem.of(remainingRefusal(null, status = 504)))
    }
}

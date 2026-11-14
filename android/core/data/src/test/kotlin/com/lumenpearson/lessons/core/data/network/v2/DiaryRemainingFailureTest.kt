package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryRemainingFailureTest {
    @Test
    fun `diary reasons distinguish own token upstream expiry and provider state`() {
        val cases = listOf(
            "DIARY_TOKEN_INVALID" to DiaryFailure.SignInRequired,
            "DIARY_REAUTH" to DiaryFailure.ReauthRequired,
            "DIARY_DISABLED" to DiaryFailure.Disabled,
            "DIARY_UNAVAILABLE" to DiaryFailure.Unavailable,
            "DIARY_UPSTREAM_UNREADABLE" to DiaryFailure.Unreadable,
            "RESOURCE_NOT_FOUND" to DiaryFailure.UnknownStudent,
            "CORRECTIONS_UNAVAILABLE" to DiaryFailure.Rejected,
        )
        for ((reason, want) in cases) assertEquals(reason, want, DiaryFailure.of(remainingRefusal(reason)))
        assertEquals(DiaryFailure.ServerAddressRefused, DiaryFailure.of(
            remainingRefusal("DIARY_UNAVAILABLE", metadata = mapOf("upstream" to "address-refused")),
        ))
    }

    @Test
    fun `diary validation keeps the call sites correction or range meaning`() {
        val error = remainingRefusal("VALIDATION_FAILED")
        assertEquals(DiaryFailure.BadRange, DiaryFailure.of(error))
        assertEquals(DiaryFailure.Rejected, DiaryFailure.of(error, DiaryFailure.Rejected))
        assertTrue(DiaryFailure.of(
            remainingRefusal("FEATURE_UNSUPPORTED", Code.UNIMPLEMENTED, 501),
        ) is DiaryFailure.Unexpected)
        assertEquals(DiaryFailure.Unavailable, DiaryFailure.of(remainingRefusal(null, status = 504)))
    }
}

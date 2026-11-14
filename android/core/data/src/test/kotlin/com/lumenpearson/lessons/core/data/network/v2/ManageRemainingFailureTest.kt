package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ManageRemainingFailureTest {
    @Test
    fun `translated management messages do not change link or role loss`() {
        for (message in listOf("Привяжите телефон", "a future wording", "")) {
            assertEquals(ManageFailure.NotLinked, ManageFailure.of(
                remainingRefusal("DEVICE_NOT_LINKED", message = message),
            ))
            assertEquals(ManageFailure.RoleLost("admin"), ManageFailure.of(
                remainingRefusal("ROLE_REQUIRED", metadata = mapOf("role" to "ROLE_ADMIN"), message = message),
            ))
            assertEquals(ManageFailure.RoleLost("owner"), ManageFailure.of(
                remainingRefusal("ROLE_REQUIRED", metadata = mapOf("role" to "ROLE_OWNER"), message = message),
            ))
        }
    }

    @Test
    fun `an unknown required role is not guessed from the message`() {
        assertEquals(ManageFailure.RoleLost(null), ManageFailure.of(
            remainingRefusal("ROLE_REQUIRED", metadata = mapOf("role" to "ROLE_FUTURE"),
                message = "admin role required"),
        ))
    }

    @Test
    fun `a refused grant keeps the management page despite a misleading message`() {
        val failure = ManageFailure.of(remainingRefusal("ROLE_GRANT_REFUSED", message = "device is not linked"))
        assertEquals(ManageFailure.NotAllowed("device is not linked"), failure)
        assertFalse(failure.endsTheSession)
    }

    @Test
    fun `management reasons win over status and retain their details`() {
        assertEquals(ManageFailure.SignedOut, ManageFailure.of(remainingRefusal("DEVICE_TOKEN_INVALID")))
        assertEquals(ManageFailure.NotFound, ManageFailure.of(remainingRefusal("RESOURCE_NOT_FOUND")))
        val refused = listOf("RESOURCE_EXISTS", "RESOURCE_IN_USE", "SUBJECT_RENAME_CLASH", "CLASS_DEVICE_NOT_LINKED")
        for (reason in refused) {
            assertEquals(reason, ManageFailure.Refused("translated"), ManageFailure.of(remainingRefusal(reason)))
        }
        for (reason in listOf("VALIDATION_FAILED", "NO_BELL_FOR_LESSON", "EMPTY_BELL_SCHEDULE",
            "TERM_BOUNDS_REFUSED", "NO_LESSON_ON_DAY", "LESSON_NOT_ON_TIMETABLE")) {
            assertEquals(reason, ManageFailure.Invalid("translated"), ManageFailure.of(remainingRefusal(reason)))
        }
        assertEquals(ManageFailure.Refused("translated"), ManageFailure.of(
            remainingRefusal("FUTURE_PRECONDITION", Code.FAILED_PRECONDITION, 400),
        ))
    }

    @Test
    fun `unknown management reasons never parse English role sentences`() {
        for (reason in listOf(null, "FUTURE_REASON")) {
            val failure = ManageFailure.of(remainingRefusal(reason, status = 403, message = "admin role required"))
            assertEquals(ManageFailure.NotAllowed("admin role required"), failure)
            assertFalse(failure.endsTheSession)
        }
        assertEquals(ManageFailure.Unavailable("translated"), ManageFailure.of(remainingRefusal(null, status = 503)))
        val future = remainingRefusal("FUTURE_REASON", Code.UNKNOWN, 500)
        assertSame(future, (ManageFailure.of(future) as ManageFailure.Unexpected).reason)
    }
}

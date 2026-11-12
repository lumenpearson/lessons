package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import java.io.IOException
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RestRefusalsTest {
    @Test
    fun `a real error info reason and its metadata survive the REST envelope`() {
        val error = RestRefusals.decode(400, """
            {"error":{"code":999,"status":"FAILED_PRECONDITION","message":"Refused",
              "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
                "reason":"A_FUTURE_REASON","metadata":{"required_role":"ROLE_ADMIN"}}]}}
        """.trimIndent())

        assertEquals(Code.FAILED_PRECONDITION, error.code)
        assertEquals("A_FUTURE_REASON", error.reason)
        assertEquals(mapOf("required_role" to "ROLE_ADMIN"), error.metadata)
        assertEquals("Refused", error.message)
        assertEquals(400, error.httpStatus)
        assertFalse((error as Throwable) is IOException)
    }

    @Test
    fun `field violations and fractional retry information are retained`() {
        val error = RestRefusals.decode(429, """
            {"error":{"status":"RESOURCE_EXHAUSTED","message":"Wait","details":[
              {"@type":"type.googleapis.com/google.rpc.BadRequest",
               "fieldViolations":[{"field":"code","description":"Too short"}]},
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3.5s"}]}}
        """.trimIndent())

        assertEquals("code", error.violations.single().field)
        assertEquals("Too short", error.violations.single().description)
        assertEquals(Duration.ofMillis(3500), error.retryDelay)
    }

    @Test
    fun `reasonless proxies keep their actual status for every body shape`() {
        val statuses = mapOf(404 to Code.NOT_FOUND, 501 to Code.UNIMPLEMENTED,
            503 to Code.UNAVAILABLE, 504 to Code.DEADLINE_EXCEEDED)
        for ((status, code) in statuses) {
            for (body in listOf("", "<html>proxy</html>", "{bad", "[]", "null", "{\"error\":{}}")) {
                val error = RestRefusals.decode(status, body)
                assertEquals(code, error.code)
                assertEquals(status, error.httpStatus)
                assertNull(error.reason)
            }
        }
    }

    @Test
    fun `reason bearing unimplemented remains distinct from a missing v2 endpoint`() {
        val error = RestRefusals.decode(501, """
            {"error":{"status":"UNIMPLEMENTED","details":[
              {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"FEATURE_UNSUPPORTED"}]}}
        """.trimIndent())

        assertEquals(Code.UNIMPLEMENTED, error.code)
        assertEquals("FEATURE_UNSUPPORTED", error.reason)
    }

    @Test
    fun `unrelated detail payloads cannot impersonate error info`() {
        val error = RestRefusals.decode(403, """
            {"error":{"status":"PERMISSION_DENIED","details":[
              {"@type":"example.invalid/OtherType","reason":"DEVICE_TOKEN_INVALID"},
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"not-duration"}]}}
        """.trimIndent())

        assertEquals(Code.PERMISSION_DENIED, error.code)
        assertNull(error.reason)
        assertNull(error.retryDelay)
    }
}

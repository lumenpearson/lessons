package com.lumenpearson.lessons.core.data.network.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «I have no revision» is not a revision (#172).
 *
 * A server whose database was built by `create_all` answers
 * `"schema": "unknown"`, and the about card drew it as «Схема unknown» the
 * first time the badge met a real server.
 */
class WarmupRevisionTest {

    @Test
    fun `the server's unknown is no revision at all`() {
        assertNull(WarmupDto(status = "degraded", schema = "unknown").revision)
        assertNull(WarmupDto(status = "degraded", schema = "UNKNOWN").revision)
        assertNull(WarmupDto(status = "degraded", schema = " ").revision)
        assertNull(WarmupDto(status = "degraded", schema = null).revision)
    }

    @Test
    fun `a real revision is passed on as it came`() {
        assertEquals("0017", WarmupDto(status = "ok", schema = "0017").revision)
    }
}

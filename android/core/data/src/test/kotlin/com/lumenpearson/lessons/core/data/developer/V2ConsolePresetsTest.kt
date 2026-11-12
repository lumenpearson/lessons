package com.lumenpearson.lessons.core.data.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConsolePresetsTest {
    @Test
    fun `v2 presets use REST and side effect free Connect GET with a complete query`() {
        val rest = ConsolePresets.filter { it.path.startsWith("/api/v2/") }
        val rpc = ConsolePresets.filter { it.path.startsWith("/api/rpc/") }
        assertTrue(rest.isNotEmpty())
        assertTrue(rpc.isNotEmpty())
        (rest + rpc).forEach { assertEquals(ConsoleMethod.GET, it.method) }
        rpc.forEach {
            assertTrue(it.path.endsWith("?encoding=json&message=%7B%7D"))
            assertTrue(it.body.isEmpty())
            assertTrue(it.headers.isEmpty())
        }
    }
}

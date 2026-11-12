package com.lumenpearson.lessons.core.data.network.v2

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCompatibilityTest {
    @Test
    fun `a background refusal is retained until a foreground claims it once`() {
        val state = CompatibilityLatch()
        state.record("CLIENT_TOO_OLD")
        state.record("DEVICE_TOKEN_INVALID")
        assertTrue(state.updateRequired.value)
        assertTrue(state.claimPrompt())
        state.record("CLIENT_TOO_OLD")
        assertFalse(state.claimPrompt())
        assertTrue(state.updateRequired.value)
    }

    @Test
    fun `other and future reasons do not demand an update`() {
        val state = CompatibilityLatch()
        listOf(null, "", "DEVICE_TOKEN_INVALID", "FUTURE_REASON").forEach(state::record)
        assertFalse(state.updateRequired.value)
        assertFalse(state.claimPrompt())
    }

    @Test
    fun `concurrent foreground claimants cannot duplicate the prompt`() {
        val state = CompatibilityLatch()
        state.record("CLIENT_TOO_OLD")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val claimed = pool.invokeAll(List(16) { Callable { state.claimPrompt() } }).count { it.get() }
            assertEquals(1, claimed)
        } finally {
            pool.shutdownNow()
        }
    }
}

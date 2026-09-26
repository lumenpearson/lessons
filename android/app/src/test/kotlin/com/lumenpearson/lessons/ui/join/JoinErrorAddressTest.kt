package com.lumenpearson.lessons.ui.join

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A join's error is an answer from one server address, and is shown only while
 * that is still the address (#176).
 *
 * The screen kept a connection error after the address under the button had
 * been changed to a working one, so it reported a refusal about an address it
 * no longer used until somebody typed or pressed again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinErrorAddressTest {

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `a connection error goes when the address it came from is changed`() = runTest {
        val rig = JoinRig().apply {
            failWith = IOException("failed to connect")
            settings.stored.value = settings.stored.value.copy(baseUrl = "http://10.0.2.2:8000")
        }
        val model = rig.model()
        val watching = launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }

        model.onCodeChange("ABCD1234")
        model.submit()
        assertEquals(JoinError.Rejected(null), model.uiState.first { it.error != null }.error)

        model.onServerUrlChange("http://127.0.0.1:8000")

        assertNull(
            "the old address's refusal is still under the field",
            model.uiState.first { it.baseUrl == "http://127.0.0.1:8000" }.error,
        )
        watching.cancel()
    }

    @Test
    fun `a wrong-length code is shown whatever the address`() = runTest {
        val rig = JoinRig()
        val model = rig.model()
        val watching = launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }

        model.onCodeChange("ABC")
        model.submit()
        model.onServerUrlChange("http://127.0.0.1:8000")

        assertEquals(
            JoinError.InvalidCode,
            model.uiState.first { it.baseUrl == "http://127.0.0.1:8000" }.error,
        )
        watching.cancel()
    }
}

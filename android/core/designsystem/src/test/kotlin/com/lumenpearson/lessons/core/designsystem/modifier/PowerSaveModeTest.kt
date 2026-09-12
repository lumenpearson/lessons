package com.lumenpearson.lessons.core.designsystem.modifier

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The battery-saver escape hatch has to work when battery saver is switched on,
 * which is the only time it is asked to.
 *
 * Read once into a `remember`, it only ever fired for somebody who was already
 * in saver mode when the screen was composed — and saver comes on once a phone
 * has been running for a while, which is to say with the app already open. The
 * shader then kept running on the surface the setting exists to spare.
 */
@RunWith(RobolectricTestRunner::class)
class PowerSaveModeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setPowerSave(on: Boolean) {
        val power = context.getSystemService(PowerManager::class.java)
        shadowOf(power).setIsPowerSaveMode(on)
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
    }

    @Test
    fun `the mode is read on the way in`() {
        setPowerSave(true)

        rule.setContent { Text(if (rememberPowerSaveMode()) "saving" else "full") }

        rule.onNodeWithText("saving").assertExists()
    }

    @Test
    fun `switching battery saver on while the screen is open is noticed`() {
        setPowerSave(false)
        rule.setContent { Text(if (rememberPowerSaveMode()) "saving" else "full") }
        rule.onNodeWithText("full").assertExists()

        setPowerSave(true)

        rule.waitForIdle()
        rule.onNodeWithText("saving").assertExists()
    }

    /** And back off again, so the blur returns without restarting the app. */
    @Test
    fun `switching it off again is noticed too`() {
        setPowerSave(true)
        rule.setContent { Text(if (rememberPowerSaveMode()) "saving" else "full") }
        rule.onNodeWithText("saving").assertExists()

        setPowerSave(false)

        rule.waitForIdle()
        rule.onNodeWithText("full").assertExists()
    }
}

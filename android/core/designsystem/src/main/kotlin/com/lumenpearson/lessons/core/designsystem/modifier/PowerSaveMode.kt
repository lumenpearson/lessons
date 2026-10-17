package com.lumenpearson.lessons.core.designsystem.modifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Whether Android's battery saver is on, for everything that draws a heavy
 * effect and for every settings row that names one (#476).
 *
 * One answer for the whole window, provided once at the root by
 * [ProvidePowerSaveMode]. While the blur under the status bar asked the
 * platform on its own, it was the only effect that listened: the ripple, the
 * scroll blur and the theme wipe went on running under battery saver, and the
 * settings rows went on reading the stored choice while the blur was not drawn.
 * A single source is what lets the effect and the row that describes it agree.
 *
 * Not static: it changes while the app is open, and a static local would
 * recompose the whole window on every change instead of the handful of
 * modifiers and rows that read it.
 *
 * The default is "not saving", which is what a `@Preview` and a test that does
 * not ask about battery saver should see. A test that does ask provides its own
 * value here — that is the fake.
 */
val LocalPowerSaveMode = compositionLocalOf { false }

/**
 * Publishes the live battery-saver state through [LocalPowerSaveMode] to
 * [content]. Wrap the window in it once, above anything that reads it — the
 * theme reveal's state is created above the theme, so this goes above that too.
 */
@Composable
fun ProvidePowerSaveMode(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPowerSaveMode provides rememberPowerSaveMode(), content = content)
}

/**
 * Whether the device is in battery-saver mode, kept up to date while this is in
 * the composition.
 *
 * Read once and remembered, which is what this used to be, the escape hatch only
 * ever worked for somebody who had switched battery saver on *before* the screen
 * was composed — and it is switched on precisely when a phone has been running
 * for a while, which is to say while the app is already open. The platform
 * announces the change; listening costs one registered receiver, and there is
 * one listener for the window: [ProvidePowerSaveMode].
 */
@Composable
internal fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var isPowerSave by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }

    // Keyed on the context too: the receiver is registered on it and must be
    // unregistered from the same one, so a new context is a new registration.
    DisposableEffect(context, power) {
        if (power == null) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isPowerSave = power.isPowerSaveMode
            }
        }
        // Not exported: only the system sends this, and a receiver that other
        // apps could reach is one they could lie to about battery saver.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Re-read on the way in as well: the mode can have changed between the
        // remember above and the registration, and that gap is a whole frame.
        isPowerSave = power.isPowerSaveMode
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return isPowerSave
}

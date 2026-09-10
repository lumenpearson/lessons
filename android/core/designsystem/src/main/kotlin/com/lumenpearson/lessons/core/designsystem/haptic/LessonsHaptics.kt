package com.lumenpearson.lessons.core.designsystem.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import com.lumenpearson.lessons.core.model.HapticStrength

/**
 * Every haptic in the app, in one object.
 *
 * A direct port of `HapticUtil` from sameerasw/essentials: a process-wide
 * `mutableStateOf` gate that every call site checks, so turning haptics off in
 * settings silences the whole app without a single component learning about the
 * preference. The strength dial is the same idea, and it is why the vibrator —
 * rather than only `View.performHapticFeedback` — is used for the two stronger
 * levels: the platform constants have no amplitude, so they cannot express one.
 *
 * [enabled] and [strength] are written by the app shell whenever the stored
 * settings change; nothing else may write them.
 */
object LessonsHaptics {

    /** Master switch; mirrors `HapticUtil.isAppHapticsEnabled`. */
    val enabled = mutableStateOf(true)

    /** Current dial position; [HapticStrength.NONE] silences everything. */
    val strength = mutableStateOf(HapticStrength.SUBTLE)

    private val isSilent: Boolean
        get() = !enabled.value || strength.value == HapticStrength.NONE

    /** A light tick: selecting a chip, flipping a switch, changing a tab. */
    fun tap(view: View) {
        if (isSilent) return
        when (strength.value) {
            HapticStrength.NONE -> Unit
            HapticStrength.SUBTLE -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            HapticStrength.DOUBLE -> waveform(view, DoublePattern, DoubleAmplitudes)
            HapticStrength.CLICK -> waveform(view, ClickPattern, ClickAmplitudes)
        }
    }

    /** The lightest feedback there is: dragging a slider past a step. */
    fun tick(view: View) {
        if (isSilent) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A deliberate press: a navigation destination, a destructive confirmation. */
    fun press(view: View) {
        if (isSilent) return
        when (strength.value) {
            HapticStrength.NONE -> Unit
            HapticStrength.SUBTLE -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            HapticStrength.DOUBLE -> waveform(view, DoublePattern, DoubleAmplitudes)
            HapticStrength.CLICK -> waveform(view, ClickPattern, ClickAmplitudes)
        }
    }

    /**
     * The bucketed rumble Essentials plays while a tab swipe is in flight.
     *
     * Deliberately not routed through [strength]: it fires ten times per swipe,
     * so a waveform here would turn a gesture into a buzz.
     */
    fun swipe(view: View) {
        if (isSilent) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun waveform(view: View, pattern: LongArray, amplitudes: IntArray) {
        val vibrator = vibratorFor(view.context)
        if (vibrator == null || !vibrator.hasVibrator()) {
            // No motor, or no access to it: fall back to the platform constant
            // so the interaction is still confirmed on the devices that fake it.
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, NoRepeat))
    }

    private fun vibratorFor(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // Both patterns are Essentials' own numbers, kept to the millisecond: they
    // are tuned to feel like a switch rather than like a notification.
    private val DoublePattern = longArrayOf(0, 40, 60, 40)
    private val DoubleAmplitudes = intArrayOf(0, 180, 0, 220)
    private val ClickPattern = longArrayOf(0, 50, 60, 30)
    private val ClickAmplitudes = intArrayOf(0, 200, 0, 150)
    private const val NoRepeat = -1
}

/**
 * The current window's `View`, which is what the platform haptics API needs.
 *
 * A one-line alias so a composable can write `val haptics = rememberHapticView()`
 * instead of importing `LocalView` and explaining itself.
 */
@Composable
fun rememberHapticView(): View = LocalView.current

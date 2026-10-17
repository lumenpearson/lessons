package com.lumenpearson.lessons.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BlurLinear
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSliderItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.modifier.LiquidRippleAnchor
import com.lumenpearson.lessons.core.designsystem.modifier.LocalPowerSaveMode
import com.lumenpearson.lessons.core.designsystem.modifier.isBlurProblematicDevice
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.navigation.labelRes
import com.lumenpearson.lessons.core.designsystem.theme.Reveal

/**
 * @param tabs whether the phone has the class tabs these rows are about — the
 *   swipe between them and the one it opens on. The diary home has neither.
 */
internal fun LazyListScope.feelRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tabs: Boolean,
) {
    item(key = "haptics") {
        SettingsGroup(title = correctedString(R.string.settings_haptics_group)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_haptics),
                subtitle = correctedString(R.string.settings_haptics_description),
                icon = Icons.Rounded.Vibration,
                tone = accentTone(2),
                checked = state.settings.hapticsEnabled,
                onCheckedChange = viewModel::setHapticsEnabled,
            )
            Reveal(visible = state.settings.hapticsEnabled) {
                GroupSegmentedItem(
                    title = correctedString(R.string.settings_haptic_strength),
                    icon = Icons.Rounded.Vibration,
                    tone = accentTone(3),
                    items = HapticStrength.entries,
                    selectedItem = state.settings.hapticStrength,
                    onItemSelected = viewModel::setHapticStrength,
                    labelProvider = { strength -> correctedString(strength.labelRes) },
                )
            }
        }
    }

    if (tabs) item(key = "navigation") {
        SettingsGroup(title = correctedString(R.string.settings_navigation_group)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_swipe_tabs),
                subtitle = correctedString(R.string.settings_swipe_tabs_description),
                icon = Icons.Rounded.Swipe,
                tone = accentTone(1),
                checked = state.settings.swipeTabs,
                onCheckedChange = viewModel::setSwipeTabs,
            )
            GroupSegmentedItem(
                title = correctedString(R.string.settings_default_tab),
                subtitle = correctedString(R.string.settings_default_tab_description),
                icon = Icons.Rounded.Widgets,
                tone = accentTone(4),
                // The reader's own order, not the declaration order. This row is
                // a picture of the bar it is about — its own comment below says
                // the glyphs would only repeat it — and a picture that lists the
                // three tabs in an order the bar no longer uses makes the reader
                // map between two of them to answer one question.
                items = state.settings.tabOrder,
                selectedItem = state.settings.defaultTab,
                onItemSelected = viewModel::setDefaultTab,
                // No icons here: even at three segments a 360 dp screen leaves
                // about 60 dp of label once an 18 dp glyph and its spacer are
                // taken out, and the glyphs would only repeat the toolbar this
                // row is about.
                labelProvider = { tab -> correctedString(tab.labelRes) },
            )
        }
    }

    item(key = "motion") {
        SettingsGroup(title = correctedString(R.string.settings_motion_group)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_animations),
                subtitle = correctedString(R.string.settings_animations_description),
                icon = Icons.Rounded.Animation,
                tone = accentTone(5),
                checked = state.settings.animations,
                onCheckedChange = viewModel::setAnimations,
            )
            // Hidden rather than disabled when animations are off: a speed for
            // something that does not move is not a dimmed control, it is a
            // question with no answer.
            Reveal(visible = state.settings.animations) {
                GroupSliderItem(
                    title = correctedString(R.string.settings_motion_speed),
                    subtitle = correctedString(R.string.settings_motion_speed_description),
                    icon = Icons.Rounded.Speed,
                    tone = accentTone(1),
                    value = state.settings.motionSpeed,
                    onValueChange = viewModel::setMotionSpeed,
                    valueRange = AppSettings.MOTION_SPEED_RANGE,
                    increment = 0.1f,
                    valueFormatter = { speed -> "%.1f×".format(speed) },
                )
            }
        }
    }

    item(key = "effects") {
        EffectsGroup(
            settings = state.settings,
            onEdgeBlur = viewModel::setEdgeBlur,
            onMotionBlur = viewModel::setMotionBlur,
            onMotionBlurScale = viewModel::setMotionBlurScale,
            onRipple = viewModel::setRippleEffects,
            onThemeReveal = viewModel::setThemeReveal,
        )
    }
}

/**
 * «Эффекты»: the four heavy effects and the strength of one of them.
 *
 * Each row shows what is drawn rather than what is stored (#476). Under
 * Android's battery saver every effect here switches itself off without
 * touching the stored choice, so a row that read the choice alone showed its
 * switch on over an effect that was not there and gave no reason — which is how
 * #444 came to be filed. Under battery saver the three rows that switch a whole effect
 * read off, cannot be pressed and say why; when it ends they read what was set,
 * because nothing was written. «Размытие под шторкой» is the exception, for the
 * reason [EdgeFadeRow] gives.
 *
 * A composable of its own, over the settings and one callback per row rather
 * than the view model, so that a test can press every row and count what it
 * asked to be stored.
 */
@Composable
internal fun EffectsGroup(
    settings: AppSettings,
    onEdgeBlur: (Boolean) -> Unit,
    onMotionBlur: (Boolean) -> Unit,
    onMotionBlurScale: (Float) -> Unit,
    onRipple: (Boolean) -> Unit,
    onThemeReveal: (Boolean) -> Unit,
) {
    // What each shader row can do on this phone right now: the API level is
    // fixed, battery saver comes and goes while the page is open.
    val saving = LocalPowerSaveMode.current
    val shaders = SupportsShaders && !saving

    SettingsGroup(title = correctedString(R.string.settings_effects_group)) {
        EdgeFadeRow(
            checked = settings.edgeBlur,
            tone = accentTone(0),
            onCheckedChange = onEdgeBlur,
        )
        GroupSwitchItem(
            title = correctedString(R.string.settings_motion_blur),
            subtitle = shaderRowLine(saving, R.string.settings_motion_blur_description),
            icon = Icons.Rounded.BlurOn,
            tone = accentTone(5),
            enabled = shaders,
            checked = settings.motionBlur && shaders,
            onCheckedChange = onMotionBlur,
        )
        Reveal(visible = settings.motionBlur && shaders) {
            GroupSliderItem(
                title = correctedString(R.string.settings_motion_blur_amount),
                icon = Icons.Rounded.MotionPhotosOn,
                tone = accentTone(3),
                value = settings.motionBlurScale,
                onValueChange = onMotionBlurScale,
                valueRange = AppSettings.MOTION_BLUR_SCALE_RANGE,
                increment = 0.1f,
                valueFormatter = { amount -> "%.1f×".format(amount) },
            )
        }
        // The two whole-screen ornaments, each on its own switch. The
        // ripple is a shader and shares the blur rows' availability; the
        // wipe is a bitmap and runs on anything, so only battery saver
        // disables it.
        // Turning the wave on plays one, from the switch that turned it
        // on. A setting whose whole subject is a visual effect can answer
        // "what is this?" by doing it once, and the answer is only honest
        // if it starts where the finger was — which is what the anchor is
        // for. Switching it off plays nothing: the modifier is already
        // disabled by the time the wave would be drawn, and a wave
        // acknowledging its own removal would be a lie about the setting.
        LiquidRippleAnchor { fire ->
            GroupSwitchItem(
                title = correctedString(R.string.settings_ripple),
                subtitle = shaderRowLine(saving, R.string.settings_ripple_description),
                icon = Icons.Rounded.Waves,
                tone = accentTone(2),
                enabled = shaders,
                checked = settings.rippleEffects && shaders,
                onCheckedChange = { on ->
                    onRipple(on)
                    if (on) fire()
                },
            )
        }
        GroupSwitchItem(
            title = correctedString(R.string.settings_theme_reveal),
            subtitle = if (saving) {
                correctedString(R.string.settings_power_save_off)
            } else {
                correctedString(R.string.settings_theme_reveal_description)
            },
            icon = Icons.Rounded.Contrast,
            tone = accentTone(4),
            enabled = !saving,
            checked = settings.themeReveal && !saving,
            onCheckedChange = onThemeReveal,
        )
    }
}

/**
 * The line under a shader row: why it is off when it is off for the phone's
 * reasons, its own description otherwise. Below Android 13 there is no shader
 * to have, which outranks battery saver — that one will not come back.
 */
@Composable
private fun shaderRowLine(saving: Boolean, @StringRes description: Int): String = correctedString(
    when {
        !SupportsShaders -> R.string.settings_blur_unavailable
        saving -> R.string.settings_power_save_off
        else -> description
    },
)

/** Label of a haptic level in the segmented picker. */
private val HapticStrength.labelRes: Int
    get() = when (this) {
        HapticStrength.NONE -> R.string.settings_haptic_none
        HapticStrength.SUBTLE -> R.string.settings_haptic_subtle
        HapticStrength.DOUBLE -> R.string.settings_haptic_double
        HapticStrength.CLICK -> R.string.settings_haptic_click
    }

/**
 * The edge fade, as one row that the settings page and the first-run flow both
 * draw.
 *
 * **It is not gated on [SupportsShaders], and that is the fix rather than an
 * oversight.** The setting turns on two things and only one of them is a
 * shader: the blur arrived in Android 13, the gradient wash under the status
 * bar and behind the toolbar is a `drawRect` that `progressiveBlur` draws on
 * everything — deliberately, because that wash is what stops a scrolled list
 * colliding with the clock on the devices that get no blur at all. The shell
 * asks for it from `AppSettings.edgeBlur` alone, and `edgeBlur` defaults to
 * true, so below Android 13 the wash was on every screen while the one switch
 * that names it read «выключено» and refused to be pressed. `minSdk` is 26, so
 * that is Android 8 through 12.
 *
 * Two copies of the row is how it got there: the first-run flow's said
 * «Доступно на Android 13 и новее» under a switch nobody could move, and the
 * settings page's said nothing at all. One row now, and the sentence it carries
 * where there is no blur says what the switch still does rather than that it
 * does nothing.
 *
 * **The same holds where the blur is switched off for the phone, not by it**
 * (#476): under battery saver, and on the Samsung builds
 * [isBlurProblematicDevice] covers, the wash is still drawn and the blur is
 * not. So this row, unlike the other three effects, neither reads off nor
 * refuses a press there — reading off over a wash it still draws is the defect
 * above, inverted — and says instead which of the two took the blur away. The
 * phone's own reason outranks battery saver: on those builds the blur does not
 * come back when battery saver ends, and a line saying «пока» would promise it.
 * Both callers, «Эффекты» and the first-run flow, get this from here.
 */
@Composable
internal fun EdgeFadeRow(
    checked: Boolean,
    tone: AccentTone,
    onCheckedChange: (Boolean) -> Unit,
) {
    GroupSwitchItem(
        title = correctedString(R.string.settings_edge_blur),
        subtitle = correctedString(
            when {
                // Each of the three ends «содержимое всё равно мягко тает у
                // краёв», which is the wash — and the shell draws the wash from
                // this same switch. Off, there is no edge to promise, so the row
                // says what it would turn on, as it does on any other phone.
                !checked -> R.string.settings_edge_blur_description
                !SupportsShaders -> R.string.settings_edge_blur_no_shader
                isBlurProblematicDevice() -> R.string.settings_edge_blur_problematic
                LocalPowerSaveMode.current -> R.string.settings_edge_blur_power_save
                else -> R.string.settings_edge_blur_description
            },
        ),
        icon = Icons.Rounded.BlurLinear,
        tone = tone,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

/** Both blur effects are AGSL runtime shaders, which arrived in Android 13. */
internal val SupportsShaders: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

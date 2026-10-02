package com.lumenpearson.lessons.ui.settings

import android.os.Build
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
        SettingsGroup(title = correctedString(R.string.settings_effects_group)) {
            EdgeFadeRow(
                checked = state.settings.edgeBlur,
                tone = accentTone(0),
                onCheckedChange = viewModel::setEdgeBlur,
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_motion_blur),
                subtitle = if (SupportsShaders) {
                    correctedString(R.string.settings_motion_blur_description)
                } else {
                    correctedString(R.string.settings_blur_unavailable)
                },
                icon = Icons.Rounded.BlurOn,
                tone = accentTone(5),
                enabled = SupportsShaders,
                checked = state.settings.motionBlur && SupportsShaders,
                onCheckedChange = viewModel::setMotionBlur,
            )
            Reveal(visible = state.settings.motionBlur && SupportsShaders) {
                GroupSliderItem(
                    title = correctedString(R.string.settings_motion_blur_amount),
                    icon = Icons.Rounded.MotionPhotosOn,
                    tone = accentTone(3),
                    value = state.settings.motionBlurScale,
                    onValueChange = viewModel::setMotionBlurScale,
                    valueRange = AppSettings.MOTION_BLUR_SCALE_RANGE,
                    increment = 0.1f,
                    valueFormatter = { amount -> "%.1f×".format(amount) },
                )
            }
            // The two whole-screen ornaments, each on its own switch. The
            // ripple is a shader and shares the blur rows' availability; the
            // wipe is a bitmap and runs on anything, so it is never disabled.
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
                    subtitle = if (SupportsShaders) {
                        correctedString(R.string.settings_ripple_description)
                    } else {
                        correctedString(R.string.settings_blur_unavailable)
                    },
                    icon = Icons.Rounded.Waves,
                    tone = accentTone(2),
                    enabled = SupportsShaders,
                    checked = state.settings.rippleEffects && SupportsShaders,
                    onCheckedChange = { on ->
                        viewModel.setRippleEffects(on)
                        if (on) fire()
                    },
                )
            }
            GroupSwitchItem(
                title = correctedString(R.string.settings_theme_reveal),
                subtitle = correctedString(R.string.settings_theme_reveal_description),
                icon = Icons.Rounded.Contrast,
                tone = accentTone(4),
                checked = state.settings.themeReveal,
                onCheckedChange = viewModel::setThemeReveal,
            )
        }
    }
}

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
 */
@Composable
internal fun EdgeFadeRow(
    checked: Boolean,
    tone: AccentTone,
    onCheckedChange: (Boolean) -> Unit,
) {
    GroupSwitchItem(
        title = correctedString(R.string.settings_edge_blur),
        subtitle = if (SupportsShaders) {
            correctedString(R.string.settings_edge_blur_description)
        } else {
            correctedString(R.string.settings_edge_blur_no_shader)
        },
        icon = Icons.Rounded.BlurLinear,
        tone = tone,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

/** Both blur effects are AGSL runtime shaders, which arrived in Android 13. */
internal val SupportsShaders: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

package com.lumenpearson.lessons.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TextFields
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.rememberCurrentAppIcon
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealAnchor
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.model.AppFont
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.ThemeMode

/**
 * Typography lands here rather than in a section of its own.
 *
 * A third page called «Шрифт и движение» was the obvious move and the wrong one:
 * it would have put the type on a page with the animation switches, which are
 * the same decision as the ripple, the theme wipe and the two blurs — all of
 * which live under «Взаимодействие». Splitting motion across two pages to keep
 * the font company costs more than the font gains. So the face and its size go
 * where everything else that repaints the whole app already is, and motion joins
 * the effects it belongs with.
 */
internal fun LazyListScope.appearanceRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenSection: (SettingsSection) -> Unit,
) {
    item(key = "appearance") {
        // Every row in this group repaints the whole app, so every row opens the
        // circle from itself. The anchor is what makes the wavefront look like it
        // came out from under the finger rather than from the middle of nowhere.
        SettingsGroup(title = correctedString(R.string.settings_theme)) {
            ThemeRevealAnchor { reveal ->
                GroupSegmentedItem(
                    title = correctedString(R.string.settings_theme_mode),
                    icon = Icons.Rounded.Contrast,
                    tone = accentTone(4),
                    items = ThemeMode.entries,
                    selectedItem = state.settings.themeMode,
                    onItemSelected = { mode -> reveal { viewModel.setThemeMode(mode) } },
                    labelProvider = { mode -> correctedString(mode.labelRes) },
                )
            }
            ThemeRevealAnchor { reveal ->
                GroupSwitchItem(
                    title = correctedString(R.string.settings_dynamic_color),
                    subtitle = if (SupportsDynamicColor) {
                        correctedString(R.string.settings_dynamic_color_description)
                    } else {
                        correctedString(R.string.settings_dynamic_color_unavailable)
                    },
                    icon = Icons.Rounded.Palette,
                    tone = accentTone(0),
                    checked = state.settings.dynamicColor && SupportsDynamicColor,
                    enabled = SupportsDynamicColor,
                    onCheckedChange = { on -> reveal { viewModel.setDynamicColor(on) } },
                )
            }
            ThemeRevealAnchor { reveal ->
                GroupSwitchItem(
                    title = correctedString(R.string.settings_pitch_black),
                    subtitle = correctedString(R.string.settings_pitch_black_description),
                    icon = Icons.Rounded.DarkMode,
                    tone = accentTone(5),
                    checked = state.settings.pitchBlack,
                    onCheckedChange = { on -> reveal { viewModel.setPitchBlack(on) } },
                )
            }
        }
    }

    // A group of its own rather than a row of the theme's: the theme repaints
    // the app at a tap, and this changes the home screen, on a page of its own.
    item(key = "app-icon") {
        SettingsGroup(title = correctedString(R.string.settings_app_icon_group)) {
            AppIconLinkRow(
                current = rememberCurrentAppIcon(),
                onOpen = { onOpenSection(SettingsSection.APP_ICON) },
            )
        }
    }

    item(key = "typography") {
        SettingsGroup(title = correctedString(R.string.settings_type_group)) {
            GroupSegmentedItem(
                title = correctedString(R.string.settings_font),
                subtitle = correctedString(R.string.settings_font_description),
                icon = Icons.Rounded.TextFields,
                tone = accentTone(2),
                items = AppFont.entries,
                selectedItem = state.settings.appFont,
                onItemSelected = viewModel::setAppFont,
                labelProvider = { font -> correctedString(font.labelRes) },
            )
            // Four named steps rather than a slider: a size is something a
            // person has to be able to put back, and "около одной целой семи"
            // is not a place anybody can return to. The labels say what each
            // step is for; the numbers behind them are in AppSettings.
            GroupSegmentedItem(
                title = correctedString(R.string.settings_text_size),
                subtitle = correctedString(R.string.settings_text_size_description),
                icon = Icons.Rounded.FormatSize,
                tone = accentTone(4),
                items = AppSettings.TEXT_SCALE_OPTIONS,
                // Matched by value rather than by identity, and against the
                // stored float rather than a remembered index: the store clamps
                // what it reads, so a value from an older build lands on the
                // nearest step instead of leaving every segment unselected.
                selectedItem = nearestTextScale(state.settings.textScale),
                onItemSelected = viewModel::setTextScale,
                labelProvider = { scale -> correctedString(textScaleLabelRes(scale)) },
            )
        }
    }

    // The language sits under the type rather than beside the theme: it is the
    // other thing on this page that changes every word on every screen, and
    // like the font it takes effect the moment it is tapped — below Android 13
    // by recreating the activity, above it by the system restarting it for us.
    item(key = "language") {
        SettingsGroup(title = correctedString(R.string.settings_language_group)) {
            GroupSegmentedItem(
                title = correctedString(R.string.settings_language),
                subtitle = correctedString(R.string.settings_language_description),
                icon = Icons.Rounded.Language,
                tone = accentTone(1),
                items = AppLanguage.entries,
                selectedItem = state.settings.language,
                onItemSelected = viewModel::setLanguage,
                labelProvider = { language -> correctedString(language.labelRes) },
            )
        }
    }
}

/** Label of a language in the segmented picker. */
private val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.RUSSIAN -> R.string.settings_language_russian
        AppLanguage.ENGLISH -> R.string.settings_language_english
    }

/**
 * The offered step closest to [scale].
 *
 * The picker has to have exactly one segment selected, and the stored value is
 * a float that a previous build — or a clamp — may have left between two steps.
 * Nearest is the only answer that never shows a picker with nothing chosen.
 */
private fun nearestTextScale(scale: Float): Float =
    AppSettings.TEXT_SCALE_OPTIONS.minBy { kotlin.math.abs(it - scale) }

/** Label of a text-size step; see `AppSettings.TEXT_SCALE_OPTIONS`. */
@StringRes
private fun textScaleLabelRes(scale: Float): Int = when (scale) {
    AppSettings.TEXT_SCALE_OPTIONS[0] -> R.string.settings_text_size_small
    AppSettings.TEXT_SCALE_OPTIONS[2] -> R.string.settings_text_size_large
    AppSettings.TEXT_SCALE_OPTIONS[3] -> R.string.settings_text_size_huge
    else -> R.string.settings_text_size_normal
}

/** Label of a typeface in the segmented picker. */
private val AppFont.labelRes: Int
    get() = when (this) {
        AppFont.BUNDLED -> R.string.settings_font_bundled
        AppFont.SYSTEM -> R.string.settings_font_system
    }

/** Label of a theme mode in the segmented picker. */
internal val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

/**
 * Dynamic colour is a wallpaper-derived palette, which only exists from Android
 * 12 on. Below that the row stays visible but disabled — hiding it would make
 * the setting look like a bug on the phones that do have it.
 */
internal val SupportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

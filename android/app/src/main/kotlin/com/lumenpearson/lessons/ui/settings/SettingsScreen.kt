package com.lumenpearson.lessons.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BlurLinear
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.BuildConfig
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSliderItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealAnchor
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.core.model.AppFont
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.navigation.labelRes
import com.lumenpearson.lessons.ui.common.ServerUrlSheet
import com.lumenpearson.lessons.ui.common.SyncIntervalOptionsMinutes
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.common.syncIntervalLabel

/**
 * One page of the settings tree.
 *
 * The preferences used to be a single scroll of six labelled groups, which is
 * how Essentials' own settings screen started and is not where it ended up: it
 * splits them across pages you drop into, each with its own title in the pill at
 * the bottom and its own way back. Two dozen rows on one page means scrolling
 * past five things you did not come for, and the row you want is never where you
 * left it because the groups above it grow and shrink with their own switches.
 *
 * The enum is the single definition of the split. The root screen lists it, the
 * section screen renders one of it, and the shell titles the toolbar from it, so
 * a new section is one entry here and one branch in [SettingsSectionScreen].
 *
 * @param tone which accent slot the row's tile takes on the root page.
 */
enum class SettingsSection(
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val icon: ImageVector,
    val tone: Int,
) {
    APPEARANCE(
        R.string.settings_appearance,
        R.string.settings_appearance_summary,
        Icons.Rounded.Palette,
        4,
    ),
    FEEL(
        R.string.settings_feel,
        R.string.settings_feel_summary,
        Icons.Rounded.TouchApp,
        2,
    ),
    CONTENT(
        R.string.settings_content,
        R.string.settings_content_summary,
        Icons.Rounded.ViewAgenda,
        3,
    ),
    // There are six accent slots and seven sections, so one hue is used twice.
    // It is shared with "О приложении", three rows further down, which is as far
    // apart as the list allows.
    ALERTS(
        R.string.settings_alerts,
        R.string.settings_alerts_summary,
        Icons.Rounded.NotificationsActive,
        5,
    ),
    SYNC(
        R.string.settings_sync,
        R.string.settings_sync_summary,
        Icons.Rounded.CloudSync,
        0,
    ),
    ACCOUNT(
        R.string.settings_class,
        R.string.settings_class_summary,
        Icons.Rounded.School,
        1,
    ),
    UPDATES(
        R.string.settings_updates,
        R.string.settings_updates_summary,
        Icons.Rounded.SystemUpdate,
        2,
    ),
    ABOUT(
        R.string.settings_about,
        R.string.settings_about_summary,
        Icons.Rounded.Info,
        5,
    ),

    /**
     * Reached from the notifications page, never from the root list.
     *
     * It is a page about a fault, so it exists only while there is one: a
     * permanent "Разрешения" row on the landing page would be one more thing to
     * read past on every visit, and would say nothing on the phones — most of
     * them — where everything is granted. [listedOnRoot] is what keeps it off.
     */
    PERMISSIONS(
        R.string.permissions_title,
        R.string.permissions_banner_description,
        Icons.Rounded.Shield,
        5,
    ) {
        override val listedOnRoot: Boolean = false
    },
    ;

    /** Whether the landing page offers a row for this section. */
    open val listedOnRoot: Boolean = true

    companion object {
        /** `null` for anything this build does not have, including `null` itself. */
        fun fromName(name: String?): SettingsSection? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The settings landing page: who you are, and where the preferences live.
 *
 * Modelled on the device page of [Essentials](https://github.com/sameerasw/essentials) —
 * a card naming the thing the page is about, then a group of rows that each open
 * a page of their own.
 */
@Composable
fun SettingsRootScreen(
    onOpenSection: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsPage(
        modifier = modifier,
        message = state.message?.asText(),
        messageKey = state.message,
        onMessageShown = viewModel::consumeMessage,
    ) {
        item(key = "class-card") {
            ClassHeroCard(
                className = state.session?.className
                    ?: stringResource(R.string.settings_class_unknown),
                school = state.session?.school
                    ?: stringResource(R.string.settings_class_no_school),
            )
        }

        item(key = "sections") {
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = stringResource(R.string.settings_sections))
                RoundedCardContainer {
                    SettingsSection.entries.filter { it.listedOnRoot }.forEach { section ->
                        GroupLinkItem(
                            title = stringResource(section.titleRes),
                            subtitle = stringResource(section.subtitleRes),
                            icon = section.icon,
                            tone = accentTone(section.tone),
                            onClick = { onOpenSection(section) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One section of the preferences, opened from the root.
 *
 * Every branch renders into the same page shape, so a section is a list of rows
 * and nothing else: no screen here owns its own scaffold, padding or snackbar.
 */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    modifier: Modifier = Modifier,
    onOpenSection: (SettingsSection) -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showServerSheet by rememberSaveable { mutableStateOf(false) }
    var showSignOutSheet by rememberSaveable { mutableStateOf(false) }
    var showUnlinkSheet by rememberSaveable { mutableStateOf(false) }

    // The link is a fact about the token that only the server holds, so the
    // class page asks on every visit. Keyed on the section: the same view
    // model serves every page, and a visit to «Оформление» is not a visit here.
    if (section == SettingsSection.ACCOUNT) {
        LaunchedEffect(Unit) { viewModel.refreshDeviceLink() }
    }

    if (showUnlinkSheet) {
        UnlinkSheet(
            onDismiss = { showUnlinkSheet = false },
            onConfirm = {
                showUnlinkSheet = false
                viewModel.unlinkDevice()
            },
        )
    }
    val sheets = rememberSupportSheets()

    SupportSheets(sheets = sheets, state = state, viewModel = viewModel)

    if (showServerSheet) {
        ServerUrlSheet(
            initialUrl = state.settings.baseUrl,
            onDismiss = { showServerSheet = false },
            onConfirm = { url ->
                viewModel.setBaseUrl(url)
                showServerSheet = false
            },
        )
    }

    if (showSignOutSheet) {
        SignOutSheet(
            className = state.session?.className,
            onDismiss = { showSignOutSheet = false },
            onConfirm = {
                showSignOutSheet = false
                viewModel.signOut()
            },
        )
    }

    SettingsPage(
        modifier = modifier,
        message = state.message?.asText(),
        messageKey = state.message,
        onMessageShown = viewModel::consumeMessage,
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(section.titleRes),
                subtitle = stringResource(section.subtitleRes),
            )
        }

        when (section) {
            SettingsSection.APPEARANCE -> appearanceRows(state, viewModel)
            SettingsSection.FEEL -> feelRows(state, viewModel)
            SettingsSection.CONTENT -> contentRows(state, viewModel)
            SettingsSection.ALERTS -> notificationRows(state, viewModel, onOpenSection)
            SettingsSection.SYNC -> syncRows(state, viewModel) { showServerSheet = true }
            SettingsSection.ACCOUNT -> {
                accountRows(state) { showSignOutSheet = true }
                telegramLinkRows(
                    state = state.deviceLink,
                    onRefresh = viewModel::refreshDeviceLink,
                    onUnlink = { showUnlinkSheet = true },
                )
            }
            SettingsSection.UPDATES -> updateRows(
                state = state,
                viewModel = viewModel,
                onShowRelease = viewModel::showReleaseSheet,
                onAskPrerelease = { sheets.prerelease = true },
            )
            SettingsSection.ABOUT -> {
                aboutRows(state, viewModel)
                supportRows(
                    state = state,
                    viewModel = viewModel,
                    onReportBug = { sheets.bugReport = true },
                    onSignIn = {
                        sheets.signIn = true
                        viewModel.signInWithGithub()
                    },
                    onShowLicenses = { sheets.licenses = true },
                )
            }
            SettingsSection.PERMISSIONS -> permissionRows()
        }
    }
}

/**
 * The shape every settings page shares.
 *
 * No `Scaffold` and no top app bar: the page starts at the top of the window so
 * that its first rows can scroll under the status bar and be softened there, and
 * its name is in the pill at the bottom. The status-bar inset is therefore part
 * of the list's content padding rather than padding around the list — padding
 * around it would stop the list short of the very bar it scrolls behind.
 */
@Composable
private fun SettingsPage(
    modifier: Modifier = Modifier,
    message: String? = null,
    messageKey: Any? = null,
    onMessageShown: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    ReportScrollOffset(listState)

    if (message != null) {
        LaunchedEffect(messageKey) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .appScrollMotionBlur(listState),
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = statusBarSpace() + 8.dp,
                bottom = LocalBottomBarSpace.current,
            ),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
            content = content,
        )

        // Lifted clear of the floating toolbar: the pill sits a few dp above the
        // navigation bar and is drawn after this, so an unlifted snackbar — the
        // app's only error feedback — appears underneath it and is never read.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalBottomBarSpace.current),
        )
    }
}

/** The card at the top of the root page, naming the class the app is signed into. */
@Composable
private fun ClassHeroCard(
    className: String,
    school: String,
    modifier: Modifier = Modifier,
) {
    RoundedCardContainer(modifier = modifier) {
        GroupRow {
            AccentIconTile(icon = Icons.Rounded.School, tone = accentTone(1))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = className,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = school,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
private fun LazyListScope.appearanceRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "appearance") {
        // Every row in this group repaints the whole app, so every row opens the
        // circle from itself. The anchor is what makes the wavefront look like it
        // came out from under the finger rather than from the middle of nowhere.
        SettingsGroup(title = stringResource(R.string.settings_theme)) {
            ThemeRevealAnchor { reveal ->
                GroupSegmentedItem(
                    title = stringResource(R.string.settings_theme_mode),
                    icon = Icons.Rounded.Contrast,
                    tone = accentTone(4),
                    items = ThemeMode.entries,
                    selectedItem = state.settings.themeMode,
                    onItemSelected = { mode -> reveal { viewModel.setThemeMode(mode) } },
                    labelProvider = { mode -> stringResource(mode.labelRes) },
                )
            }
            ThemeRevealAnchor { reveal ->
                GroupSwitchItem(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = if (SupportsDynamicColor) {
                        stringResource(R.string.settings_dynamic_color_description)
                    } else {
                        stringResource(R.string.settings_dynamic_color_unavailable)
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
                    title = stringResource(R.string.settings_pitch_black),
                    subtitle = stringResource(R.string.settings_pitch_black_description),
                    icon = Icons.Rounded.DarkMode,
                    tone = accentTone(5),
                    checked = state.settings.pitchBlack,
                    onCheckedChange = { on -> reveal { viewModel.setPitchBlack(on) } },
                )
            }
        }
    }

    item(key = "typography") {
        SettingsGroup(title = stringResource(R.string.settings_type_group)) {
            GroupSegmentedItem(
                title = stringResource(R.string.settings_font),
                subtitle = stringResource(R.string.settings_font_description),
                icon = Icons.Rounded.TextFields,
                tone = accentTone(2),
                items = AppFont.entries,
                selectedItem = state.settings.appFont,
                onItemSelected = viewModel::setAppFont,
                labelProvider = { font -> stringResource(font.labelRes) },
            )
            // Four named steps rather than a slider: a size is something a
            // person has to be able to put back, and "около одной целой семи"
            // is not a place anybody can return to. The labels say what each
            // step is for; the numbers behind them are in AppSettings.
            GroupSegmentedItem(
                title = stringResource(R.string.settings_text_size),
                subtitle = stringResource(R.string.settings_text_size_description),
                icon = Icons.Rounded.FormatSize,
                tone = accentTone(4),
                items = AppSettings.TEXT_SCALE_OPTIONS,
                // Matched by value rather than by identity, and against the
                // stored float rather than a remembered index: the store clamps
                // what it reads, so a value from an older build lands on the
                // nearest step instead of leaving every segment unselected.
                selectedItem = nearestTextScale(state.settings.textScale),
                onItemSelected = viewModel::setTextScale,
                labelProvider = { scale -> stringResource(textScaleLabelRes(scale)) },
            )
        }
    }

    // The language sits under the type rather than beside the theme: it is the
    // other thing on this page that changes every word on every screen, and
    // like the font it takes effect the moment it is tapped — below Android 13
    // by recreating the activity, above it by the system restarting it for us.
    item(key = "language") {
        SettingsGroup(title = stringResource(R.string.settings_language_group)) {
            GroupSegmentedItem(
                title = stringResource(R.string.settings_language),
                subtitle = stringResource(R.string.settings_language_description),
                icon = Icons.Rounded.Language,
                tone = accentTone(1),
                items = AppLanguage.entries,
                selectedItem = state.settings.language,
                onItemSelected = viewModel::setLanguage,
                labelProvider = { language -> stringResource(language.labelRes) },
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

private fun LazyListScope.feelRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "haptics") {
        SettingsGroup(title = stringResource(R.string.settings_haptics_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_haptics),
                subtitle = stringResource(R.string.settings_haptics_description),
                icon = Icons.Rounded.Vibration,
                tone = accentTone(2),
                checked = state.settings.hapticsEnabled,
                onCheckedChange = viewModel::setHapticsEnabled,
            )
            if (state.settings.hapticsEnabled) {
                GroupSegmentedItem(
                    title = stringResource(R.string.settings_haptic_strength),
                    icon = Icons.Rounded.Vibration,
                    tone = accentTone(3),
                    items = HapticStrength.entries,
                    selectedItem = state.settings.hapticStrength,
                    onItemSelected = viewModel::setHapticStrength,
                    labelProvider = { strength -> stringResource(strength.labelRes) },
                )
            }
        }
    }

    item(key = "navigation") {
        SettingsGroup(title = stringResource(R.string.settings_navigation_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_swipe_tabs),
                subtitle = stringResource(R.string.settings_swipe_tabs_description),
                icon = Icons.Rounded.Swipe,
                tone = accentTone(1),
                checked = state.settings.swipeTabs,
                onCheckedChange = viewModel::setSwipeTabs,
            )
            GroupSegmentedItem(
                title = stringResource(R.string.settings_default_tab),
                subtitle = stringResource(R.string.settings_default_tab_description),
                icon = Icons.Rounded.Widgets,
                tone = accentTone(4),
                items = HomeTab.entries,
                selectedItem = state.settings.defaultTab,
                onItemSelected = viewModel::setDefaultTab,
                // No icons here: even at three segments a 360 dp screen leaves
                // about 60 dp of label once an 18 dp glyph and its spacer are
                // taken out, and the glyphs would only repeat the toolbar this
                // row is about.
                labelProvider = { tab -> stringResource(tab.labelRes) },
            )
        }
    }

    item(key = "motion") {
        SettingsGroup(title = stringResource(R.string.settings_motion_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_animations),
                subtitle = stringResource(R.string.settings_animations_description),
                icon = Icons.Rounded.Animation,
                tone = accentTone(5),
                checked = state.settings.animations,
                onCheckedChange = viewModel::setAnimations,
            )
            // Hidden rather than disabled when animations are off: a speed for
            // something that does not move is not a dimmed control, it is a
            // question with no answer.
            if (state.settings.animations) {
                GroupSliderItem(
                    title = stringResource(R.string.settings_motion_speed),
                    subtitle = stringResource(R.string.settings_motion_speed_description),
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
        SettingsGroup(title = stringResource(R.string.settings_effects_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_edge_blur),
                subtitle = stringResource(R.string.settings_edge_blur_description),
                icon = Icons.Rounded.BlurLinear,
                tone = accentTone(0),
                enabled = SupportsShaders,
                checked = state.settings.edgeBlur && SupportsShaders,
                onCheckedChange = viewModel::setEdgeBlur,
            )
            GroupSwitchItem(
                title = stringResource(R.string.settings_motion_blur),
                subtitle = if (SupportsShaders) {
                    stringResource(R.string.settings_motion_blur_description)
                } else {
                    stringResource(R.string.settings_blur_unavailable)
                },
                icon = Icons.Rounded.BlurOn,
                tone = accentTone(5),
                enabled = SupportsShaders,
                checked = state.settings.motionBlur && SupportsShaders,
                onCheckedChange = viewModel::setMotionBlur,
            )
            if (state.settings.motionBlur && SupportsShaders) {
                GroupSliderItem(
                    title = stringResource(R.string.settings_motion_blur_amount),
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
            GroupSwitchItem(
                title = stringResource(R.string.settings_ripple),
                subtitle = if (SupportsShaders) {
                    stringResource(R.string.settings_ripple_description)
                } else {
                    stringResource(R.string.settings_blur_unavailable)
                },
                icon = Icons.Rounded.Waves,
                tone = accentTone(2),
                enabled = SupportsShaders,
                checked = state.settings.rippleEffects && SupportsShaders,
                onCheckedChange = viewModel::setRippleEffects,
            )
            GroupSwitchItem(
                title = stringResource(R.string.settings_theme_reveal),
                subtitle = stringResource(R.string.settings_theme_reveal_description),
                icon = Icons.Rounded.Contrast,
                tone = accentTone(4),
                checked = state.settings.themeReveal,
                onCheckedChange = viewModel::setThemeReveal,
            )
        }
    }
}

private fun LazyListScope.contentRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) = item(key = "content") {
    SettingsGroup(title = stringResource(R.string.settings_content_group)) {
        GroupSwitchItem(
            title = stringResource(R.string.settings_show_teacher),
            subtitle = stringResource(R.string.settings_show_teacher_description),
            icon = Icons.Rounded.Person,
            tone = accentTone(3),
            checked = state.settings.showTeacher,
            onCheckedChange = viewModel::setShowTeacher,
        )
        GroupSwitchItem(
            title = stringResource(R.string.settings_widget_progress),
            subtitle = stringResource(R.string.settings_widget_progress_description),
            icon = Icons.Rounded.Widgets,
            tone = accentTone(1),
            checked = state.settings.widgetShowProgress,
            onCheckedChange = viewModel::setWidgetShowProgress,
        )
    }
}

private fun LazyListScope.syncRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onEditServer: () -> Unit,
) = item(key = "sync") {
    SettingsGroup(title = stringResource(R.string.settings_sync_group)) {
        SyncIntervalRow(
            selectedMinutes = state.settings.syncIntervalMinutes,
            onSelect = viewModel::setSyncInterval,
        )
        GroupLinkItem(
            title = stringResource(R.string.settings_server_url),
            subtitle = state.settings.baseUrl.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.settings_server_url_default),
            icon = Icons.Rounded.Dns,
            tone = accentTone(0),
            onClick = onEditServer,
        )
        // Filled and full width, as the last row of the group, because it is
        // the one thing on this page that *does* something the moment it is
        // pressed rather than storing a preference. Essentials closes its own
        // updates group with the same shape.
        GroupActionItem(
            label = stringResource(R.string.settings_refresh_now),
            icon = Icons.Rounded.Refresh,
            busy = state.isRefreshing,
            onClick = viewModel::refreshNow,
        )
    }
}

private fun LazyListScope.accountRows(
    state: SettingsUiState,
    onSignOut: () -> Unit,
) = item(key = "class") {
    SettingsGroup(title = stringResource(R.string.settings_class_group)) {
        GroupItem(
            title = state.session?.className
                ?: stringResource(R.string.settings_class_unknown),
            subtitle = state.session?.school
                ?: stringResource(R.string.settings_class_no_school),
            icon = Icons.Rounded.School,
            tone = accentTone(1),
        )
        GroupItem(
            title = stringResource(R.string.settings_sign_out),
            icon = Icons.AutoMirrored.Rounded.Logout,
            tone = errorTone(),
            onClick = onSignOut,
        )
    }
}

private fun LazyListScope.aboutRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "about") {
        SettingsGroup(title = stringResource(R.string.settings_about_group)) {
            GroupSwitchItem(
                title = stringResource(R.string.settings_debug),
                subtitle = stringResource(R.string.settings_debug_description),
                icon = Icons.Rounded.BugReport,
                tone = accentTone(2),
                checked = state.settings.debugMode,
                onCheckedChange = viewModel::setDebugMode,
            )
        }
    }
    // The last thing on the last page, which is where an about block belongs and
    // where Essentials puts its own. It carries the version, the description and
    // the design credit, so the three rows that used to state those separately
    // are gone rather than repeated above it.
    item(key = "about_card") {
        AboutCard(modifier = Modifier.padding(horizontal = ScreenPadding))
    }
}

/**
 * Dynamic colour is a wallpaper-derived palette, which only exists from Android
 * 12 on. Below that the row stays visible but disabled — hiding it would make
 * the setting look like a bug on the phones that do have it.
 */
internal val SupportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Both blur effects are AGSL runtime shaders, which arrived in Android 13. */
internal val SupportsShaders: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** A labelled group; the one place the label-to-group spacing is decided. */
@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        RoundedCardContainer(content = content)
    }
}

/** Label of a theme mode in the segmented picker. */
internal val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
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
 * Sync cadence as chips rather than a slider: the values are not continuous —
 * WorkManager will not run periodic work more often than every 15 minutes — and
 * a chip row makes the actual choices legible.
 */
@Composable
private fun SyncIntervalRow(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GroupRow(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) {
        AccentIconTile(icon = Icons.Rounded.Update, tone = accentTone(4))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_sync_interval),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // FlowRow because six chips do not fit one line on a small phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SyncIntervalOptionsMinutes.forEach { minutes ->
                    PillChip(
                        text = syncIntervalLabel(minutes),
                        selected = minutes == selectedMinutes,
                        onClick = { onSelect(minutes) },
                    )
                }
            }
        }
    }
}

/**
 * Confirmation before signing out.
 *
 * Cheap insurance: the code needed to get back in lives on a teacher's list, not
 * in the pupil's head. A sheet rather than a dialog, so that the one destructive
 * action in the app arrives from the same edge as everything else.
 */
@Composable
private fun SignOutSheet(
    className: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sign_out_title),
    ) {
        Text(
            text = className
                ?.let { stringResource(R.string.settings_sign_out_message, it) }
                ?: stringResource(R.string.settings_sign_out_message_generic),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(text = stringResource(R.string.settings_sign_out))
            }
        }
    }
}

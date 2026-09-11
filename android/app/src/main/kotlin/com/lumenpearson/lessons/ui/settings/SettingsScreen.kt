package com.lumenpearson.lessons.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BlurLinear
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.BuildConfig
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSliderItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.navigation.icon
import com.lumenpearson.lessons.navigation.labelRes
import com.lumenpearson.lessons.ui.common.ServerUrlSheet
import com.lumenpearson.lessons.ui.common.SyncIntervalOptionsMinutes
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.common.syncIntervalLabel

/**
 * Preferences, class membership and the about box.
 *
 * Written as plain grouped rows rather than a preference library: there are two
 * dozen settings, they are all backed by one `AppSettings` object, and a library
 * would add a second definition of every one of them.
 *
 * The personalization block is modelled on the "Customizations" section of
 * [Essentials](https://github.com/sameerasw/essentials) — the same options, in
 * the same order, in the same component vocabulary: a toggle row per switch, a
 * connected button group per choice, and a slider row with step buttons for the
 * one continuous value.
 *
 * Each row gets its own hue. On a screen where every row is a title, a subtitle
 * and a switch, the colour of the tile is the only thing that lets a returning
 * user find the one row they came for without reading.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    var showServerSheet by rememberSaveable { mutableStateOf(false) }
    var showSignOutSheet by rememberSaveable { mutableStateOf(false) }

    state.message?.let { message ->
        val text = message.asText()
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

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

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LessonsTopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = {
            // Lifted clear of the floating toolbar. Scaffold puts the host a
            // few dp above the navigation bar, which is exactly where the pill
            // is, and the pill is drawn after it — so the app's only error
            // feedback was appearing underneath the bar and then being consumed.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalBottomBarSpace.current),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .appScrollMotionBlur(listState),
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 4.dp,
                bottom = LocalBottomBarSpace.current,
            ),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
        ) {
            item(key = "appearance") {
                SettingsGroup(title = stringResource(R.string.settings_appearance)) {
                    GroupSegmentedItem(
                        title = stringResource(R.string.settings_theme_mode),
                        icon = Icons.Rounded.Contrast,
                        tone = accentTone(4),
                        items = ThemeMode.entries,
                        selectedItem = state.settings.themeMode,
                        onItemSelected = viewModel::setThemeMode,
                        labelProvider = { mode -> stringResource(mode.labelRes) },
                    )
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
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                    GroupSwitchItem(
                        title = stringResource(R.string.settings_pitch_black),
                        subtitle = stringResource(R.string.settings_pitch_black_description),
                        icon = Icons.Rounded.DarkMode,
                        tone = accentTone(5),
                        checked = state.settings.pitchBlack,
                        onCheckedChange = viewModel::setPitchBlack,
                    )
                }
            }

            item(key = "feel") {
                SettingsGroup(title = stringResource(R.string.settings_feel)) {
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
                        // No icons here. Four segments on a 360 dp screen leave
                        // about 38 dp of label once an 18 dp glyph and its
                        // spacer are taken out, so every option read as "Сег…",
                        // "Нед…", "Зад…", "Нас…". The icons also only repeated
                        // the toolbar this row is about.
                        labelProvider = { tab -> stringResource(tab.labelRes) },
                    )
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
                }
            }

            item(key = "content") {
                SettingsGroup(title = stringResource(R.string.settings_content)) {
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

            item(key = "sync") {
                SettingsGroup(title = stringResource(R.string.settings_sync)) {
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
                        onClick = { showServerSheet = true },
                    )
                    GroupItem(
                        title = stringResource(R.string.settings_refresh_now),
                        icon = Icons.Rounded.Refresh,
                        tone = accentTone(3),
                        enabled = !state.isRefreshing,
                        onClick = viewModel::refreshNow,
                        trailing = {
                            if (state.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                    )
                }
            }

            item(key = "class") {
                SettingsGroup(title = stringResource(R.string.settings_class)) {
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
                        onClick = { showSignOutSheet = true },
                    )
                }
            }

            item(key = "about") {
                SettingsGroup(title = stringResource(R.string.settings_about)) {
                    GroupItem(
                        title = stringResource(R.string.app_name),
                        subtitle = stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                        ),
                        icon = Icons.Rounded.Info,
                        tone = accentTone(5),
                    )
                    GroupRow {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.about_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.about_design_credit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dynamic colour is a wallpaper-derived palette, which only exists from Android
 * 12 on. Below that the row stays visible but disabled — hiding it would make
 * the setting look like a bug on the phones that do have it.
 */
private val SupportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Both blur effects are AGSL runtime shaders, which arrived in Android 13. */
private val SupportsShaders: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** A labelled group; the one place the label-to-group spacing is decided. */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        RoundedCardContainer(content = content)
    }
}

/** Label of a theme mode in the segmented picker. */
private val ThemeMode.labelRes: Int
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

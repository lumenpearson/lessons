package com.lumenpearson.lessons.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
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
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupCard
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.ui.common.FloatingBarSpace
import com.lumenpearson.lessons.ui.common.ServerUrlDialog
import com.lumenpearson.lessons.ui.common.SyncIntervalOptionsMinutes
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.common.syncIntervalLabel

/**
 * Preferences, class membership and the about box.
 *
 * Written as plain grouped rows rather than a preference library: there are nine
 * settings, they are all backed by one `AppSettings` object, and a library would
 * add a second definition of every one of them.
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
    var showServerDialog by rememberSaveable { mutableStateOf(false) }
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }

    state.message?.let { message ->
        val text = message.asText()
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    if (showServerDialog) {
        ServerUrlDialog(
            initialUrl = state.settings.baseUrl,
            onDismiss = { showServerDialog = false },
            onConfirm = { url ->
                viewModel.setBaseUrl(url)
                showServerDialog = false
            },
        )
    }

    if (showSignOutDialog) {
        SignOutDialog(
            className = state.session?.className,
            onDismiss = { showSignOutDialog = false },
            onConfirm = {
                showSignOutDialog = false
                viewModel.signOut()
            },
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LessonsTopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 4.dp,
                bottom = FloatingBarSpace,
            ),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
        ) {
            item(key = "appearance") {
                SettingsGroup(title = stringResource(R.string.settings_appearance)) {
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
                        onClick = { showServerDialog = true },
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
                        icon = Icons.Rounded.Logout,
                        tone = errorTone(),
                        onClick = { showSignOutDialog = true },
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
                        Text(
                            text = stringResource(R.string.about_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

/** A labelled group; the one place the label-to-group spacing is decided. */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        GroupCard(content = content)
    }
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
 * in the pupil's head.
 */
@Composable
private fun SignOutDialog(
    className: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_sign_out_title)) },
        text = {
            Text(
                text = className
                    ?.let { stringResource(R.string.settings_sign_out_message, it) }
                    ?: stringResource(R.string.settings_sign_out_message_generic),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_sign_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

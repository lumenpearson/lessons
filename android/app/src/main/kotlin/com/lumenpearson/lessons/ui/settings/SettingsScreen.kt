package com.lumenpearson.lessons.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.lumenpearson.lessons.core.designsystem.component.LessonsTopAppBar
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.ui.common.ServerUrlDialog
import com.lumenpearson.lessons.ui.common.SyncIntervalOptionsMinutes
import com.lumenpearson.lessons.ui.common.asText
import com.lumenpearson.lessons.ui.common.syncIntervalLabel

/**
 * Preferences, class membership and the about box.
 *
 * Written as a plain list of rows rather than a preference library: there are
 * nine settings, they are all backed by one `AppSettings` object, and a library
 * would add a second definition of every one of them.
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // --- Оформление ---------------------------------------------------
            item(key = "appearance-header") {
                SectionHeader(stringResource(R.string.settings_appearance))
            }
            item(key = "dynamic-color") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    description = if (SupportsDynamicColor) {
                        stringResource(R.string.settings_dynamic_color_description)
                    } else {
                        stringResource(R.string.settings_dynamic_color_unavailable)
                    },
                    checked = state.settings.dynamicColor && SupportsDynamicColor,
                    enabled = SupportsDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
            item(key = "pitch-black") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_pitch_black),
                    description = stringResource(R.string.settings_pitch_black_description),
                    checked = state.settings.pitchBlack,
                    onCheckedChange = viewModel::setPitchBlack,
                )
            }

            // --- Содержимое ---------------------------------------------------
            item(key = "content-header") {
                SectionHeader(stringResource(R.string.settings_content))
            }
            item(key = "show-teacher") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_show_teacher),
                    description = stringResource(R.string.settings_show_teacher_description),
                    checked = state.settings.showTeacher,
                    onCheckedChange = viewModel::setShowTeacher,
                )
            }
            item(key = "widget-progress") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_widget_progress),
                    description = stringResource(R.string.settings_widget_progress_description),
                    checked = state.settings.widgetShowProgress,
                    onCheckedChange = viewModel::setWidgetShowProgress,
                )
            }

            // --- Синхронизация ------------------------------------------------
            item(key = "sync-header") {
                SectionHeader(stringResource(R.string.settings_sync))
            }
            item(key = "sync-interval") {
                SyncIntervalRow(
                    selectedMinutes = state.settings.syncIntervalMinutes,
                    onSelect = viewModel::setSyncInterval,
                )
            }
            item(key = "server-url") {
                SettingsClickableRow(
                    title = stringResource(R.string.settings_server_url),
                    description = state.settings.baseUrl.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_server_url_default),
                    onClick = { showServerDialog = true },
                )
            }
            item(key = "refresh-now") {
                Button(
                    onClick = viewModel::refreshNow,
                    enabled = !state.isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_refresh_now),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            // --- Класс ----------------------------------------------------------
            item(key = "class-header") {
                SectionHeader(stringResource(R.string.settings_class))
            }
            item(key = "class-info") {
                SettingsInfoRow(
                    title = state.session?.className
                        ?: stringResource(R.string.settings_class_unknown),
                    description = state.session?.school
                        ?: stringResource(R.string.settings_class_no_school),
                )
            }
            item(key = "sign-out") {
                OutlinedButton(
                    onClick = { showSignOutDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_sign_out),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            // --- О приложении ---------------------------------------------------
            item(key = "about-header") {
                SectionHeader(stringResource(R.string.settings_about))
            }
            item(key = "about-body") {
                SettingsInfoRow(
                    title = stringResource(R.string.app_name),
                    description = stringResource(
                        R.string.about_version,
                        BuildConfig.VERSION_NAME,
                    ) + "\n" + stringResource(R.string.about_description),
                )
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

/** A title, an explanation, and a switch — the shape every toggle here takes. */
@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SettingsSurface(
        modifier = modifier,
        onClick = if (enabled) {
            { onCheckedChange(!checked) }
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** A row that opens a dialog. */
@Composable
private fun SettingsClickableRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSurface(modifier = modifier, onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row that only states a fact. */
@Composable
private fun SettingsInfoRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    SettingsSurface(modifier = modifier, onClick = null) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The shared container: one shape and one padding for every settings row. */
@Composable
private fun SettingsSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_sync_interval),
            style = MaterialTheme.typography.titleMedium,
        )
        // FlowRow because six chips do not fit one line on a small phone.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncIntervalOptionsMinutes.forEach { minutes ->
                PillChip(
                    syncIntervalLabel(minutes),
                    selected = minutes == selectedMinutes,
                    onClick = { onSelect(minutes) },
                )
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

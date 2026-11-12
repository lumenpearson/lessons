package com.lumenpearson.lessons.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.R as DataR
import com.lumenpearson.lessons.core.data.network.v2.ClientCompatibility
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString

/** Also present on welcome/join: an obsolete APK may never reach HomeShell. */
@Composable
internal fun ClientUpdateHost() {
    val required by ClientCompatibility.updateRequired.collectAsStateWithLifecycle()
    if (!required) return

    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var prompt by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (ClientCompatibility.claimPrompt()) prompt = true
    }
    if (prompt) {
        AlertDialog(
            onDismissRequest = { prompt = false },
            title = { Text(correctedString(DataR.string.client_update_required)) },
            text = { Text(correctedString(DataR.string.client_update_required_detail)) },
            confirmButton = {
                TextButton(onClick = { prompt = false; viewModel.checkForUpdates() }) {
                    Text(correctedString(R.string.update_sheet_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { prompt = false }) { Text(correctedString(R.string.update_sheet_later)) }
            },
        )
    }
    UpdateHost(state, viewModel, ripple = null, screenToRoot = { it }, compatibilityHost = true)
}

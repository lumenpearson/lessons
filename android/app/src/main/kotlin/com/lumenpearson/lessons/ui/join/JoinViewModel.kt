package com.lumenpearson.lessons.ui.join

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.ui.common.ClassCodeLength
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Why joining failed, in a form the view model may hold.
 *
 * Split from a plain string so the screen can style the two cases differently:
 * a malformed code is the user's to fix, a rejection is the server's to explain.
 */
sealed interface JoinError {

    /** Fewer than [ClassCodeLength] characters typed. */
    data object InvalidCode : JoinError

    /** The server refused the code, or was unreachable. */
    data class Rejected(val detail: String?) : JoinError
}

/**
 * @property code the 6-character invite code, already normalized to upper case.
 * @property baseUrl current server address, shown as a link under the button.
 * @property isSubmitting a request is in flight; the button shows a spinner.
 * @property error inline error under the field, cleared on the next keystroke.
 */
data class JoinUiState(
    val code: String = "",
    val baseUrl: String = "",
    val isSubmitting: Boolean = false,
    val error: JoinError? = null,
) {
    /** The button is only live for a complete code with no request running. */
    val canSubmit: Boolean get() = code.length == ClassCodeLength && !isSubmitting
}

/**
 * First-run screen state.
 *
 * Success is deliberately *not* reported back through a callback: joining writes
 * a session, the app shell observes the session, and navigation follows from
 * that. One mechanism, so signing out later cannot take a different path back.
 *
 * @param deviceName sent with the join request so a teacher can tell one pupil's
 *   phone from another in the class admin panel.
 */
class JoinViewModel(
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceName: String?,
) : ViewModel() {

    private val code = MutableStateFlow("")
    private val submitting = MutableStateFlow(false)
    private val error = MutableStateFlow<JoinError?>(null)

    val uiState: StateFlow<JoinUiState> = combine(
        code,
        submitting,
        error,
        settingsRepository.settings.map { it.baseUrl },
    ) { code, isSubmitting, error, baseUrl ->
        JoinUiState(
            code = code,
            baseUrl = baseUrl,
            isSubmitting = isSubmitting,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = JoinUiState(),
    )

    /**
     * Normalizes as the user types: codes are printed on paper in upper case and
     * never contain punctuation, so accepting anything else only creates a
     * failed round-trip.
     */
    fun onCodeChange(raw: String) {
        code.value = raw
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .take(ClassCodeLength)
        error.value = null
    }

    /** Stores a new server address; takes effect on the next request. */
    fun onServerUrlChange(url: String) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(baseUrl = url) }
        }
    }

    /** Sends the code. The result reaches the UI as a session, or as an error. */
    fun submit() {
        val value = code.value
        if (value.length != ClassCodeLength) {
            error.value = JoinError.InvalidCode
            return
        }
        if (submitting.value) return

        viewModelScope.launch {
            submitting.value = true
            error.value = null
            val result = sessionRepository.join(value, deviceName)
            submitting.value = false
            result.exceptionOrNull()?.let { failure ->
                error.value = JoinError.Rejected(failure.message?.takeIf { it.isNotBlank() })
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                JoinViewModel(
                    sessionRepository = Graph.container.sessionRepository,
                    settingsRepository = Graph.container.settingsRepository,
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                )
            }
        }
    }
}

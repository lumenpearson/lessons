package com.lumenpearson.lessons.ui.join

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.JoinFailure
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.ui.common.ClassCodeLengths
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

    /** The typed code is not a length [ClassCodeLengths] allows. */
    data object InvalidCode : JoinError

    /** No class and no live invite answers to this code. */
    data object UnknownCode : JoinError

    /**
     * The code is real and the class no longer admits anybody who merely knows
     * it.
     *
     * Its own case because it is the only failure on this screen whose answer
     * is not on this screen: the way in is a personal code out of the bot, and
     * «неизвестный код» would send the user back to whoever read the class code
     * out to them, who cannot help.
     */
    data object InviteOnly : JoinError

    /**
     * Too many failed attempts from this address; [minutes] is how long the
     * throttle says to wait, rounded up, or null when it would not say.
     *
     * Minutes rather than the seconds the header carries: nobody waits 743
     * seconds, and a number that precise invites watching it rather than
     * putting the phone down.
     */
    data class TooManyAttempts(val minutes: Int?) : JoinError

    /**
     * The server refused the code some other way, or was unreachable.
     *
     * [detail] is what the server said, and it is null when nobody said
     * anything worth repeating — no network, or a failure whose only message
     * is one this app wrote in English for a log. The screen then falls back
     * to its own Russian sentence, which is the whole reason the field is
     * nullable.
     */
    data class Rejected(val detail: String?) : JoinError

    companion object {

        /** What the repository answered, as something the screen can word. */
        fun of(failure: Throwable): JoinError = when (val classified = JoinFailure.of(failure)) {
            JoinFailure.InviteOnly -> InviteOnly
            JoinFailure.UnknownCode -> UnknownCode
            is JoinFailure.TooManyAttempts -> TooManyAttempts(
                // Rounded up, and never to zero: «подождите 0 минут» is an
                // instruction to do nothing, and the wait is real.
                minutes = classified.retryAfterSeconds?.let { (it + 59) / 60 }?.coerceAtLeast(1),
            )
            // `classified.message`, never the raw `failure`'s. Every
            // `JoinFailure` supplies an English fallback message so that a log
            // line is never empty, and reading those here made the null branch
            // unreachable: «Не удалось подключиться: Could not reach the
            // server» went on a Russian screen, and the Russian sentence
            // written for exactly that case was never shown again.
            is JoinFailure.Offline -> Rejected(classified.reason.message?.takeIf { it.isNotBlank() })
            is JoinFailure.Rejected ->
                Rejected(classified.reason?.message?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * @property code the invite code, already normalized to upper case.
 * @property baseUrl current server address, shown as a link under the button.
 * @property isSubmitting a request is in flight; the button shows a spinner.
 * @property error inline error under the field, cleared on the next keystroke.
 */
data class JoinUiState(
    val code: String = "",
    val baseUrl: String = "",
    val isSubmitting: Boolean = false,
    val error: JoinError? = null,
    /**
     * The class just joined, until somebody consumes it.
     *
     * The screen ignores this — joining writes a session and the shell
     * navigates on the session, which is still the one mechanism. It exists
     * for the «Добавить класс» sheet, which is raised from inside an app that
     * is already signed in: nothing navigates there, so the sheet has to be
     * told, and the class id alone cannot tell it. Re-entering the code of the
     * class already on screen is a real case — it is how somebody whose device
     * was revoked gets back in — and it leaves the active class exactly as it
     * was while having plainly succeeded.
     */
    val joinedClassId: Long? = null,
) {
    /** The button is only live for a complete code with no request running. */
    val canSubmit: Boolean get() = code.length in ClassCodeLengths && !isSubmitting
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
    private val timetableRepository: TimetableRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceName: String?,
) : ViewModel() {

    private val code = MutableStateFlow("")
    private val submitting = MutableStateFlow(false)
    private val error = MutableStateFlow<JoinError?>(null)
    private val joined = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<JoinUiState> = combine(
        code,
        submitting,
        error,
        joined,
        settingsRepository.settings.map { it.baseUrl },
    ) { code, isSubmitting, error, joinedClassId, baseUrl ->
        JoinUiState(
            code = code,
            baseUrl = baseUrl,
            isSubmitting = isSubmitting,
            error = error,
            joinedClassId = joinedClassId,
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
            .take(ClassCodeLengths.last)
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
        if (value.length !in ClassCodeLengths) {
            error.value = JoinError.InvalidCode
            return
        }
        if (submitting.value) return

        viewModelScope.launch {
            submitting.value = true
            error.value = null
            val result = sessionRepository.join(value, deviceName)
            val failure = result.exceptionOrNull()
            if (failure != null) {
                submitting.value = false
                error.value = JoinError.of(failure)
                return@launch
            }
            // Pull the timetable straight away. Joining only stores a token;
            // without this the first data arrives whenever the periodic worker
            // next happens to run — up to an hour later, and longer still
            // because that worker had already fired once, before there was a
            // session to sync, and consumed its slot. Until then both the app
            // and the widget say "расписание ещё не загружено" to somebody who
            // has just this second joined a class.
            timetableRepository.refresh()
            submitting.value = false
            // Last, so that whoever is watching for it sees a finished join
            // rather than one still fetching its first week.
            joined.value = result.getOrNull()?.classId
        }
    }

    /** Clears the one-shot in [JoinUiState.joinedClassId]. */
    fun consumeJoined() {
        joined.value = null
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                JoinViewModel(
                    sessionRepository = Graph.container.sessionRepository,
                    timetableRepository = Graph.container.timetableRepository,
                    settingsRepository = Graph.container.settingsRepository,
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                )
            }
        }
    }
}

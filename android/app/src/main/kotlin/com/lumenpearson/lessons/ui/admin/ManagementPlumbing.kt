package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ManageFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The management page's one state, and the four ways every sheet touches it.
 *
 * One object under all seven sheets' actions rather than a copy in each,
 * because what they share is the point: one [state], so a refusal [note]
 * recognises on any sheet empties the page for all eight; one `working` flag,
 * so two writes never race on the same class; and one place that drops an
 * answer arriving after the page has gone.
 *
 * Launches in [viewModelScope], the view model's, which is cancelled when the
 * view model is cleared, and which is why the parameter carries that name.
 */
internal class ManagementPlumbing(private val viewModelScope: CoroutineScope) {

    val state = MutableStateFlow(ManagementUiState())

    /** A read: mark it in flight, run it, and let [note] see whatever it answers. */
    fun read(
        begin: (ManagementUiState) -> ManagementUiState,
        block: suspend () -> Unit,
    ) {
        if (state.value.gone != null) return
        state.update(begin)
        viewModelScope.launch { block() }
    }

    /**
     * A write: one at a time, and its failure kept where the screen can show it.
     *
     * Refused rather than queued while another write is in flight, because two
     * of these change the same row and the second would be answered against a
     * class the user has not seen yet.
     */
    fun write(block: suspend () -> Result<*>) {
        if (state.value.gone != null || state.value.working) return
        state.update { it.copy(working = true, writeFailure = null) }
        viewModelScope.launch {
            val failure = block().exceptionOrNull()?.let(ManageFailure::of)
            // [note] rewrites the whole state when it recognises the refusal, so
            // it runs before this update rather than inside it: an update that
            // updates again is one of the two that loses.
            if (!note(failure)) {
                state.update { it.copy(working = false, writeFailure = failure) }
            }
        }
    }

    /** Stores a read's answer, after [note] has had its say about the failure. */
    fun <T> finish(
        result: Result<T>,
        apply: (ManagementUiState, T?, ManageFailure?) -> ManagementUiState,
    ) {
        val failure = result.exceptionOrNull()?.let(ManageFailure::of)
        note(failure)
        // Nothing is written once the page has gone — whether this very failure
        // took it away or one that landed while this read was in flight. A read
        // started under the old role answers about a class that is no longer
        // this user's, and [note] having emptied the state would be undone by
        // the answer arriving a moment behind it: invisible until «Проверить
        // снова» succeeded, at which point the page came back holding the
        // previous administrator's subjects.
        state.update { if (it.gone != null) it else apply(it, result.getOrNull(), failure) }
    }

    /**
     * The one place a refusal is read as "the page is no longer yours".
     *
     * @return whether it was one, so the caller can drop the failure instead of
     *   also drawing it next to the button: the page is about to say the whole
     *   of it, once, and an error card underneath would be the same news twice.
     */
    fun note(failure: ManageFailure?): Boolean {
        if (failure == null || !failure.endsTheSession) return false
        state.update {
            // Nothing that was read under the old role is shown again: it was
            // true for somebody who is not this user any more.
            ManagementUiState(gone = failure)
        }
        return true
    }
}

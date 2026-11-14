package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ManageFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Each asynchronous operation belongs to the UI generation that initiated it. */
internal class ManagementPlumbing(private val viewModelScope: CoroutineScope) {
    val state = MutableStateFlow(ManagementUiState())

    fun reset() {
        state.update { ManagementUiState(operationGeneration = it.operationGeneration + 1) }
    }

    fun operation(): Operation = Operation(state.value.operationGeneration)

    fun read(begin: (ManagementUiState) -> ManagementUiState, block: suspend Operation.() -> Unit) {
        if (state.value.gone != null) return
        val operation = operation()
        operation.update(begin)
        viewModelScope.launch { if (operation.isCurrent) operation.block() }
    }

    fun write(block: suspend Operation.() -> Result<*>) {
        val before = state.value
        if (before.gone != null || before.working) return
        if (!state.compareAndSet(before, before.copy(working = true, writeFailure = null))) return
        val operation = Operation(before.operationGeneration)
        viewModelScope.launch {
            if (!operation.isCurrent) return@launch
            val failure = operation.block().exceptionOrNull()?.let(ManageFailure::of)
            if (!operation.note(failure)) {
                operation.update { it.copy(working = false, writeFailure = failure) }
            }
        }
    }

    inner class Operation internal constructor(private val generation: Long) {
        val isCurrent: Boolean get() = state.value.operationGeneration == generation

        fun update(change: (ManagementUiState) -> ManagementUiState) {
            // The revision and UI value share one CAS so reset cannot be overtaken by a late answer.
            state.update { if (it.operationGeneration == generation) change(it) else it }
        }

        fun <T> Result<T>.onCurrentSuccess(action: (T) -> Unit): Result<T> {
            if (isCurrent) onSuccess(action)
            return this
        }

        fun <T> finish(result: Result<T>, apply: (ManagementUiState, T?, ManageFailure?) -> ManagementUiState) {
            val failure = result.exceptionOrNull()?.let(ManageFailure::of)
            if (note(failure)) return
            update { if (it.gone != null) it else apply(it, result.getOrNull(), failure) }
        }

        fun note(failure: ManageFailure?): Boolean {
            if (failure == null || !failure.endsTheSession) return false
            var ended = false
            state.update {
                if (it.operationGeneration != generation) it else {
                    ended = true
                    ManagementUiState(gone = failure, operationGeneration = generation + 1)
                }
            }
            return ended
        }
    }
}

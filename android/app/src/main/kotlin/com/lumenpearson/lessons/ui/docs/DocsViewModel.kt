package com.lumenpearson.lessons.ui.docs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.DocsRepository
import com.lumenpearson.lessons.core.data.repository.DocsState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The guide, for the shell and the screen that draws it.
 *
 * It owns nothing but the asking. The repository holds the state — which is
 * what lets the toolbar, which is composed by the shell, and the pages, which
 * are composed inside it, read the same pages without one of them passing them
 * to the other.
 *
 * The language is handed in rather than read here: below Android 13 the app's
 * chosen language is applied by wrapping the `Context`, so the composition is
 * the only place that knows which one a reader is actually looking at.
 */
class DocsViewModel(private val repository: DocsRepository) : ViewModel() {

    val state: StateFlow<DocsState> = repository.state

    /**
     * Called as the guide opens: shows what this phone already has, then asks
     * GitHub whether there is anything newer.
     *
     * In that order and in one coroutine, so the pages are on screen before the
     * network is touched. The reverse — waiting for a fetch to draw anything —
     * is the arrangement that makes documentation useless exactly when it is
     * needed, which is on a school network that does not resolve GitHub.
     */
    fun open(language: String) {
        viewModelScope.launch {
            repository.load(language)
            repository.refresh(language)
        }
    }

    /** The pull-to-refresh gesture. */
    fun refresh(language: String) {
        viewModelScope.launch { repository.refresh(language) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DocsViewModel(repository = Graph.container.docsRepository) }
        }
    }
}

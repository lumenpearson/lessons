package com.lumenpearson.lessons.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconStore
import com.lumenpearson.lessons.appicon.AppIconVariant
import com.lumenpearson.lessons.appicon.AppIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What «Значок приложения» shows.
 *
 * @param current the icon the launcher shows, and the default until it has
 *   been read.
 * @param applying a switch is running, so «Применить» is held.
 * @param failed the last switch was refused, which the page says once.
 */
data class AppIconUiState(
    val current: AppIconVariant = AppIconCatalog.default,
    val applying: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The page's state over [AppIconStore]. The tile the reader has selected is
 * the screen's own, saved with it; only an applied icon reaches here.
 */
class AppIconViewModel(private val store: AppIconStore) : ViewModel() {

    private val applying = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)

    val uiState: StateFlow<AppIconUiState> = combine(store.current, applying, failed) { current, applying, failed ->
        AppIconUiState(current = current ?: AppIconCatalog.default, applying = applying, failed = failed)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        // Seeded from whatever the store already knows, so a page opened
        // after the store's own refresh has landed does not flash the
        // default for the one frame before this flow's first collection.
        AppIconUiState(current = store.current.value ?: AppIconCatalog.default),
    )

    init {
        // The launcher is the record, and it can have changed since the store
        // last looked: an update, and the reconcile after it.
        viewModelScope.launch { runCatching { store.refresh() } }
    }

    /** Switches to [target], unless a switch is already running or it is the icon in use. */
    fun apply(target: AppIconVariant) {
        if (applying.value || target == (store.current.value ?: AppIconCatalog.default)) return
        applying.value = true
        viewModelScope.launch {
            failed.value = runCatching { store.switchTo(target) }.isFailure
            applying.value = false
        }
    }

    fun consumeFailure() {
        failed.value = false
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                AppIconViewModel(AppIcons.store(application))
            }
        }
    }
}

package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.model.AppLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A change to a setting the widget draws asks the widget to redraw (#188).
 *
 * Switched to Russian on the emulator, the app redrew at once and the widget
 * went on drawing English: nothing but a sync that changed the data, or the
 * widget's own tick, ever asked it to draw again, and on a day off the tick is
 * hours away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsRedrawTest {

    private class MemoryStore : SettingsStore {
        val current = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = current
        override fun languageBlocking(): AppLanguage = current.value.language
        override suspend fun currentSettings(): AppSettings = current.value
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
            current.value = transform(current.value)
        }
    }

    private var redraws = 0

    private fun repository() = SettingsRepositoryImpl(
        preferences = MemoryStore(),
        ioDispatcher = UnconfinedTestDispatcher(),
        onWidgetSettingsChanged = { redraws++ },
    )

    @Test
    fun `every setting the widget draws asks it to redraw`() = runTest {
        val repository = repository()

        repository.update { it.copy(language = AppLanguage.RUSSIAN) }
        repository.update { it.copy(widgetShowProgress = false) }
        repository.update { it.copy(showTeacher = false) }

        assertEquals(3, redraws)
    }

    @Test
    fun `a setting the widget does not draw leaves it alone`() = runTest {
        val repository = repository()

        repository.update { it.copy(pitchBlack = true) }
        repository.update { it.copy(hapticsEnabled = false) }
        repository.update { it.copy(todayShowHero = false) }

        assertEquals(0, redraws)
    }

    @Test
    fun `writing the value it already has is not a change`() = runTest {
        val repository = repository()

        repository.update { it.copy(language = AppLanguage.SYSTEM) }

        assertEquals(0, redraws)
    }
}

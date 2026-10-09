package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconStore
import com.lumenpearson.lessons.appicon.FakeLauncherComponents
import com.lumenpearson.lessons.appicon.LauncherAliases
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The page's one button, against a launcher that agrees, refuses, or is slow. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppIconViewModelTest {

    private val default = AppIconCatalog.default
    private val other = AppIconCatalog.variants.first { it != default }

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun TestScope.model(
        components: FakeLauncherComponents,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): AppIconViewModel {
        val model = AppIconViewModel(AppIconStore(LauncherAliases(components), io = io))
        // uiState is shared while subscribed, as it is while the page is on screen.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        return model
    }

    @Test
    fun `the page opens on the icon the launcher shows`() = runTest {
        val model = model(FakeLauncherComponents(mapOf(other.alias to true, default.alias to false)))

        assertEquals(other, model.uiState.value.current)
    }

    @Test
    fun `until the launcher has been read the page shows the default`() = runTest {
        val model = model(FakeLauncherComponents(), io = StandardTestDispatcher(testScheduler))

        assertEquals(default, model.uiState.value.current)
        advanceUntilIdle()
        assertEquals(default, model.uiState.value.current)
    }

    @Test
    fun `apply switches the icon and the page follows`() = runTest {
        val components = FakeLauncherComponents()
        val model = model(components)

        model.apply(other)

        assertEquals(other, model.uiState.value.current)
        assertFalse(model.uiState.value.applying)
        assertEquals(listOf(other), components.enabled())
    }

    @Test
    fun `a refused switch is reported once and changes nothing`() = runTest {
        val model = model(FakeLauncherComponents(failure = SecurityException("refused")))

        model.apply(other)

        assertTrue(model.uiState.value.failed)
        assertEquals(default, model.uiState.value.current)
        assertFalse("«Применить» stays usable", model.uiState.value.applying)
        model.consumeFailure()
        assertFalse(model.uiState.value.failed)
    }

    @Test
    fun `a second press while the first is still switching does nothing`() = runTest {
        val components = FakeLauncherComponents()
        val model = model(components, io = StandardTestDispatcher(testScheduler))

        model.apply(other)
        model.apply(AppIconCatalog.variants.last())
        assertTrue(model.uiState.value.applying)
        advanceUntilIdle()

        assertEquals(1, components.calls.size)
        assertEquals(other, model.uiState.value.current)
    }

    @Test
    fun `pressing apply on the icon in use writes nothing`() = runTest {
        val components = FakeLauncherComponents()
        val model = model(components)

        model.apply(default)

        assertEquals(emptyList<Any>(), components.calls)
    }
}

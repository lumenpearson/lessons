package com.lumenpearson.lessons.ui

import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.data.repository.SyncArming
import com.lumenpearson.lessons.navigation.RootScreen
import com.lumenpearson.lessons.navigation.ShellHome
import com.lumenpearson.lessons.navigation.rootScreen
import com.lumenpearson.lessons.navigation.shellHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The shell's state holder: the stored mode, read after the cold start has been
 * settled, and followed from then on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {

    private class FakeShellMode(initial: ShellState) : ShellModeSource {
        val stored = MutableStateFlow(initial)
        val calls = mutableListOf<String>()

        override val state: Flow<ShellState> = stored.onStart { calls += "state" }
        override suspend fun current(): ShellState = stored.value.also { calls += "current" }
        override val syncArming: Flow<SyncArming> = emptyFlow()
        override suspend fun hold() {
            stored.value = stored.value.copy(held = true)
        }
        override suspend fun release() {
            stored.value = stored.value.copy(held = false)
        }
        override suspend fun settleColdStart(): ShellState {
            calls += "settle"
            if (stored.value.mode == ShellMode.NONE) stored.value = stored.value.copy(held = false)
            return stored.value
        }
    }

    private class FakeSettings : SettingsRepository by unused() {
        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** #151, end to end through the view model: diary and no class is the diary home. */
    @Test
    fun `a diary without a class is the diary home, not the join screen`() {
        val source = FakeShellMode(ShellState(ShellMode.DIARY, held = false))
        val model = AppShellViewModel(source, FakeSettings(), AppShellViewModel.ColdStart())

        val shell = model.uiState.value.shell
        assertEquals(RootScreen.HOME, rootScreen(shell))
        assertEquals(ShellHome.DIARY, shell?.mode?.let(::shellHome))
    }

    @Test
    fun `leaving the last class with a diary signed in moves to the diary home`() {
        val source = FakeShellMode(ShellState(ShellMode.CLASS, held = false))
        val model = AppShellViewModel(source, FakeSettings(), AppShellViewModel.ColdStart())
        assertEquals(ShellMode.CLASS, model.uiState.value.shell?.mode)

        source.stored.value = ShellState(ShellMode.DIARY, held = false)

        assertEquals(ShellMode.DIARY, model.uiState.value.shell?.mode)
        assertEquals(RootScreen.HOME, rootScreen(model.uiState.value.shell))
    }

    /**
     * A hold left by a flow whose credential never landed is dropped before the
     * gate reads anything — and only once per process: the activity is rebuilt
     * on every rotation, and the hold a running onboarding has just set must
     * survive that.
     */
    @Test
    fun `the cold start is settled once per process, before the first read`() {
        val source = FakeShellMode(ShellState(ShellMode.NONE, held = true))
        val process = AppShellViewModel.ColdStart()

        val first = AppShellViewModel(source, FakeSettings(), process)
        assertEquals("settle", source.calls.first())
        assertEquals(ShellState(ShellMode.NONE, held = false), first.uiState.value.shell)

        source.stored.value = ShellState(ShellMode.NONE, held = true)
        val rebuilt = AppShellViewModel(source, FakeSettings(), process)

        assertEquals(1, source.calls.count { it == "settle" })
        assertEquals(ShellState(ShellMode.NONE, held = true), rebuilt.uiState.value.shell)
        assertEquals(RootScreen.ONBOARDING, rootScreen(rebuilt.uiState.value.shell))
    }
}

/** A delegate for the members a fake does not override: a call to one is a test bug. */
private inline fun <reified T : Any> unused(): T = java.lang.reflect.Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("${T::class.simpleName}.${method.name} was not expected here") } as T

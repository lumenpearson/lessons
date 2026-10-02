package com.lumenpearson.lessons.ui.today

import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.Timetable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * A refresh that does not come back normally (#224).
 *
 * The flag was lowered only on the normal return, so a cancelled refresh left
 * «Сегодня»'s pull-to-refresh indicator parked over its title — and the guard
 * that refuses a second refresh while one runs then refused every later pull.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayRefreshTest {

    private var calls = 0

    private val timetables = object : TimetableRepository {
        override val timetable: Flow<Timetable?> = MutableStateFlow(null)
        override val syncedYears: Flow<Set<Int>> = MutableStateFlow(emptySet())
        override suspend fun snapshot(): Timetable? = null
        override suspend fun snapshotAroundToday(): Timetable? = null
        override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit
        override suspend fun refresh(): SyncResult {
            calls++
            if (calls == 1) throw CancellationException("the screen went away")
            return SyncResult.Success
        }
        override suspend fun refreshYear(openingYear: Int): SyncResult = SyncResult.Success
    }

    private val settings = object : SettingsRepository {
        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        override fun languageBlocking() = AppLanguage.SYSTEM
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a cancelled refresh lowers the indicator and the next pull still runs`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = TodayViewModel(timetableRepository = timetables, settingsRepository = settings)
            backgroundScope.launch { viewModel.uiState.collect { } }

            viewModel.refresh()
            assertFalse("the indicator is still up", viewModel.uiState.value.isRefreshing)

            viewModel.refresh()
            assertEquals("the second pull was refused", 2, calls)
        }
}

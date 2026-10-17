package com.lumenpearson.lessons.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.BellsWritten
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SchoolPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the eight sheets share through the one view model, beyond the page
 * being taken away from all of them at once, which `ManagementViewModelTest`
 * holds.
 *
 * Written before each sheet's work moved into a class of its own, and run on
 * the code before the move, so that the move is proved by something: one
 * write at a time across every sheet, the devices' switch read when a list is
 * asked for rather than when anything was built, and work that stops with
 * the view model instead of outliving it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagementAcrossSheetsTest {

    private val repository = FakeManageRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() =
        ManagementViewModel(repository, FakeDeviceLinkRepository(ClassRole.ADMIN), FakeSessionRepository())

    /**
     * The page's one write flag belongs to the page, not to a sheet: two
     * writes on the same class at once are what it exists to stop, from
     * whichever two sheets they come.
     */
    @Test
    fun `a write on one sheet is refused while another sheet's write is in flight`() {
        val model = model()
        model.saveBellPeriods(3L, emptyList())

        model.deleteSubject(ManagedSubject(1L, "Алгебра", null, null, null))
        assertEquals(1, repository.pending.size)
        assertTrue(model.uiState.value.working)

        repository.answer(0, Result.success(BellsWritten(BELLS, silencedLessons = 0)))
        assertFalse(model.uiState.value.working)
    }

    /**
     * A list asked for without saying which, by the sheet opening or by a
     * revoke reloading it, asks for what the switch shows now.
     */
    @Test
    fun `a device list asked for without a choice follows the switch as it stands`() {
        val model = model()
        model.loadDevices(includeRevoked = true)
        repository.answer(0, Result.success(emptyList<ManagedDevice>()))

        model.loadDevices()
        repository.answer(1, Result.success(emptyList<ManagedDevice>()))
        model.revokeDevice(device(1L))
        repository.answer(2, Result.success(device(1L)))

        assertEquals(listOf(true, true, true), repository.deviceCalls)
    }

    /**
     * Work the view model started stops with it: a search still out when the
     * screen's store is cleared lands nowhere.
     */
    @Test
    fun `a search still out when the view model is cleared does not land`() {
        val store = ViewModelStore()
        val model = ViewModelProvider(store, factoryOf(model()))[ManagementViewModel::class.java]
        model.searchSchools("лицей")

        store.clear()
        repository.answer(0, Result.success(
            SchoolPage(emptyList(), page = 1, pages = 1, total = 0, truncated = false),
        ))

        assertTrue(model.uiState.value.schoolSearch.searching)
        assertFalse(model.uiState.value.schoolSearch.searched)
    }

    private fun device(id: Long) = ManagedDevice(
        id = id,
        deviceName = "Pixel $id",
        linked = true,
        owner = "@anna",
        role = ClassRole.EDITOR,
        revoked = false,
        createdAt = null,
        lastSeenAt = null,
        linkedAt = null,
    )

    private companion object {
        val BELLS = BellSchedule(id = 3L, name = "Обычное", isDefault = true, periods = emptyList())
    }
}

/** Hands [model] to a store, the way the activity's provider would. */
private fun factoryOf(model: ViewModel) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
}

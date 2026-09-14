package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.School
import com.lumenpearson.lessons.core.data.repository.SchoolPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding a school from the phone.
 *
 * The two things worth pinning are the two that cost something when they are
 * wrong: a page turn must not become a second search — the directory has no
 * offset, so every request to the server is a fresh search upstream — and a
 * directory that is switched off must leave the form usable, because typing
 * the name is what this screen did before the search existed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchoolSearchTest {

    private val repository = FakeManageRepository()
    private val links = FakeDeviceLinkRepository(ClassRole.ADMIN)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ManagementViewModel(repository, links, FakeSessionRepository())

    private fun schools(count: Int): List<School> = (0 until count).map {
        School(
            name = "МБОУ \"Школа № $it\"",
            fullName = "ШКОЛА $it",
            ogrn = "$it",
            address = null,
            city = "Казань",
            region = null,
            active = true,
        )
    }

    private fun page(items: List<School>, truncated: Boolean = false) = SchoolPage(
        items = items,
        page = 1,
        pages = 1,
        total = items.size,
        truncated = truncated,
    )

    @Test
    fun `a search keeps everything it found and shows the first five`() {
        val model = viewModel()

        model.searchSchools("школа казань")
        repository.answer(0, Result.success(page(schools(12))))

        val search = model.uiState.value.schoolSearch
        assertEquals(12, search.results.size)
        assertEquals(5, search.visible.size)
        assertEquals(3, search.pages)
        assertFalse(search.searching)
        assertTrue(search.searched)
    }

    @Test
    fun `turning a page does not search again`() {
        val model = viewModel()
        model.searchSchools("школа казань")
        repository.answer(0, Result.success(page(schools(12))))

        model.showSchoolPage(3)

        assertEquals(1, repository.schoolQueries.size)
        assertEquals(3, model.uiState.value.schoolSearch.page)
        assertEquals(2, model.uiState.value.schoolSearch.visible.size)
    }

    @Test
    fun `a page past either end is clamped rather than shown empty`() {
        val model = viewModel()
        model.searchSchools("школа")
        repository.answer(0, Result.success(page(schools(7))))

        model.showSchoolPage(99)
        assertEquals(2, model.uiState.value.schoolSearch.page)

        model.showSchoolPage(0)
        assertEquals(1, model.uiState.value.schoolSearch.page)
    }

    @Test
    fun `twenty results are reported as the first twenty, not as the total`() {
        val model = viewModel()

        model.searchSchools("школа")
        repository.answer(0, Result.success(page(schools(20), truncated = true)))

        assertTrue(model.uiState.value.schoolSearch.truncated)
    }

    @Test
    fun `a directory that is switched off is a sentence, not a failure`() {
        val model = viewModel()

        model.searchSchools("гимназия 3")
        repository.answer(0, Result.failure(ManageFailure.Unavailable("Введите название вручную")))

        val search = model.uiState.value.schoolSearch
        assertEquals("Введите название вручную", search.unavailable)
        assertNull(search.failure)
        // And the page is still the user's: nothing about a missing key says
        // anything about this phone's role.
        assertNull(model.uiState.value.gone)
    }

    @Test
    fun `an ordinary failure is kept where the panel can draw it`() {
        val model = viewModel()

        model.searchSchools("гимназия 3")
        repository.answer(0, Result.failure(ManageFailure.Offline(RuntimeException("no net"))))

        val search = model.uiState.value.schoolSearch
        assertTrue(search.failure is ManageFailure.Offline)
        assertNull(search.unavailable)
        assertFalse(search.searching)
    }

    @Test
    fun `an answer to a query the user has moved on from is dropped`() {
        val model = viewModel()

        model.searchSchools("гимн")
        model.searchSchools("гимназия 3")
        // The first search answers last, as a slow one does.
        repository.answer(1, Result.success(page(schools(2))))
        repository.answer(0, Result.success(page(schools(9))))

        val search = model.uiState.value.schoolSearch
        assertEquals("гимназия 3", search.query)
        assertEquals(2, search.results.size)
    }

    @Test
    fun `a demoted role still empties the page, search or not`() {
        val model = viewModel()

        model.searchSchools("школа")
        repository.answer(0, Result.failure(ManageFailure.RoleLost("admin")))

        assertTrue(model.uiState.value.gone is ManageFailure.RoleLost)
    }

    @Test
    fun `clearing forgets the results so a reopened sheet starts empty`() {
        val model = viewModel()
        model.searchSchools("школа")
        repository.answer(0, Result.success(page(schools(6))))

        model.clearSchoolSearch()

        val search = model.uiState.value.schoolSearch
        assertTrue(search.results.isEmpty())
        assertFalse(search.searched)
        assertEquals(1, search.page)
    }

    @Test
    fun `a label carries the city because two schools share a number`() {
        val one = schools(1).single()
        assertEquals("МБОУ \"Школа № 0\" · Казань", one.label)
        assertEquals("МБОУ \"Школа № 0\"", one.copy(city = null).label)
    }
}

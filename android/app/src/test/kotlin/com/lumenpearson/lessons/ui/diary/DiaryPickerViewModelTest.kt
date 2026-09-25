package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.catalog.CatalogNetSchool
import com.lumenpearson.lessons.core.data.catalog.CatalogRecommendation
import com.lumenpearson.lessons.core.data.catalog.CatalogRegion
import com.lumenpearson.lessons.core.data.catalog.CatalogSystem
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.core.data.upstream.DiarySchoolSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The diary picker: a region, then — for a «Сетевой город» region — a school,
 * and the answer is the diary to sign in to, with no login and nothing secret.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryPickerViewModelTest {

    private val main = StandardTestDispatcher()

    private val petersburg = CatalogRegion(
        key = "saint-petersburg",
        nameRu = "Санкт-Петербург",
        nameEn = "Saint Petersburg",
        recommended = CatalogRecommendation(platform = "petersburg", system = 0),
        systems = listOf(CatalogSystem(platform = "petersburg", action = "signin")),
    )
    private val samara = CatalogRegion(
        key = "samara",
        nameRu = "Самарская область",
        nameEn = "Samara Oblast",
        zone = "Europe/Samara",
        recommended = CatalogRecommendation(platform = "netschool", system = 0),
        systems = listOf(
            CatalogSystem(
                platform = "netschool",
                action = "signin",
                netschool = CatalogNetSchool("samara", "https://asurso.ru", password = true, zone = "Europe/Samara"),
            ),
        ),
    )

    private val asked = mutableListOf<Pair<String, String>>()
    private var answer: Result<List<DiarySchool>> = Result.success(listOf(DiarySchool(77, "Школа № 7", "ул. Ленина, 1")))

    private fun model() = DiaryPickerViewModel(
        regions = { query -> listOf(petersburg, samara).filter { query.isBlank() || it.nameRu.contains(query) } },
        schools = { region, query ->
            asked += region to query
            answer
        },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Petersburg is answered the moment it is tapped`() {
        val model = model()
        main.scheduler.advanceUntilIdle()

        model.pickRegion(model.state.value.regions.first { it.key == "saint-petersburg" })

        assertEquals(DiaryTarget.petersburg(""), model.state.value.picked)
    }

    @Test
    fun `a «Сетевой город» region asks its own server for schools, after the typing stops`() {
        val model = model()
        main.scheduler.advanceUntilIdle()
        model.pickRegion(model.state.value.regions.first { it.key == "samara" })
        assertNull(model.state.value.picked)

        model.searchSchools("Ш")
        model.searchSchools("Шк")
        model.searchSchools("Школа 7")
        main.scheduler.advanceTimeBy(DiarySchoolSearch.DEBOUNCE_MILLIS - 1)
        main.scheduler.runCurrent()
        assertEquals("nothing asked while typing", emptyList<Pair<String, String>>(), asked)

        main.scheduler.advanceUntilIdle()
        assertEquals(listOf("samara" to "Школа 7"), asked)

        model.pickSchool(model.state.value.schools.single())
        assertEquals(
            DiaryTarget.netschool("samara", 77, "Школа № 7", login = "", zone = "Europe/Samara"),
            model.state.value.picked,
        )
    }

    @Test
    fun `a one-letter query asks nobody`() {
        val model = model()
        main.scheduler.advanceUntilIdle()
        model.pickRegion(model.state.value.regions.first { it.key == "samara" })

        model.searchSchools("Ш")
        main.scheduler.advanceUntilIdle()

        assertEquals(emptyList<Pair<String, String>>(), asked)
    }

    @Test
    fun `a region server that refuses the phone is said in the diary's words`() {
        answer = Result.failure(DiarySignInProblem.ProviderRefusesPhone("asurso.ru"))
        val model = model()
        main.scheduler.advanceUntilIdle()
        model.pickRegion(model.state.value.regions.first { it.key == "samara" })

        model.searchSchools("Школа")
        main.scheduler.advanceUntilIdle()

        assertEquals(DiarySignInProblem.ProviderRefusesPhone("asurso.ru"), model.state.value.problem)
    }
}

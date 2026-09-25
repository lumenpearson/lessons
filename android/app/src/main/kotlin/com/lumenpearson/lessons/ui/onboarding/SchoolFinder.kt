package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.core.data.upstream.DiarySchoolSearch
import com.lumenpearson.lessons.ui.diary.DiarySchoolRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @property problem why the region's server did not answer — the same server
 *   the password goes to next, so `DiaryProblemText` words it (K20).
 */
@Immutable
data class SchoolSearchUi(
    val regionKey: String? = null,
    val query: String = "",
    val rows: List<DiarySchoolRow> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val problem: DiarySignInProblem? = null,
)

/**
 * The school step's search, straight from the phone to the region's own
 * «Сетевой город» server (K27). That server is where the password goes next,
 * so a failure here is the honest early warning that the sign-in could not
 * reach it either. Debounced and floored by the search's own constants.
 */
class SchoolFinder(
    private val scope: CoroutineScope,
    private val schools: suspend (regionKey: String, query: String) -> Result<List<DiarySchool>>,
    private val debounceMillis: Long = DiarySchoolSearch.DEBOUNCE_MILLIS,
) {
    private val mutable = MutableStateFlow(SchoolSearchUi())
    val state: StateFlow<SchoolSearchUi> = mutable.asStateFlow()

    private var job: Job? = null

    /** A region picked: start over, with the query the region step carried, asked at once. */
    fun open(regionKey: String, prefill: String) {
        if (mutable.value.regionKey == regionKey && mutable.value.query.isNotEmpty()) return
        job?.cancel()
        mutable.value = SchoolSearchUi(regionKey = regionKey, query = prefill)
        search(prefill, debounce = false)
    }

    fun type(query: String) {
        mutable.update { it.copy(query = query) }
        search(query, debounce = true)
    }

    fun retry() = search(mutable.value.query, debounce = false)

    private fun search(query: String, debounce: Boolean) {
        val region = mutable.value.regionKey ?: return
        job?.cancel()
        if (query.trim().length < DiarySchoolSearch.MIN_QUERY_LENGTH) {
            mutable.update { it.copy(rows = emptyList(), searching = false, searched = false, problem = null) }
            return
        }
        job = scope.launch {
            if (debounce) delay(debounceMillis)
            mutable.update { it.copy(searching = true, problem = null) }
            schools(region, query).fold(
                onSuccess = { found ->
                    mutable.update {
                        it.copy(
                            searching = false,
                            searched = true,
                            rows = found.map { school -> DiarySchoolRow(school.id, school.name, school.address) },
                        )
                    }
                },
                onFailure = { failure ->
                    mutable.update {
                        it.copy(
                            searching = false,
                            searched = true,
                            rows = emptyList(),
                            problem = failure as? DiarySignInProblem ?: DiarySignInProblem.of(failure),
                        )
                    }
                },
            )
        }
    }
}

package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.data.catalog.DirectoryProblem
import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.catalog.RegionLookupResult
import com.lumenpearson.lessons.core.data.catalog.RegionMatch
import com.lumenpearson.lessons.core.data.catalog.SchoolDirectory
import com.lumenpearson.lessons.core.data.catalog.SchoolLookup
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the school-name search said nothing useful. Every one ends in «выберите регион из списка». */
enum class DirectoryFailure { WAIT, NO_SERVER, OFFLINE, OFF }

/** What the directory said about the name typed, if it was asked. */
sealed interface SchoolAnswer {
    /** The query reads as a place, or is too short: nothing was asked. */
    data object NotAsked : SchoolAnswer

    /** A name too common to place — «школа № 5» is in every region. */
    data object Generic : SchoolAnswer

    @Immutable
    data class Found(val hits: List<SchoolHitUi>, val truncated: Boolean) : SchoolAnswer

    /** [minutes] is how long to wait, rounded up, for [DirectoryFailure.WAIT]. */
    @Immutable
    data class Failed(val reason: DirectoryFailure, val minutes: Int? = null) : SchoolAnswer
}

/**
 * A region the directory placed schools of that name in.
 *
 * @property regionKey `null` when the bundled catalog cannot place it — then it
 *   is named by [label] and cannot be picked: no diary can be offered for a
 *   region the catalog does not know.
 */
@Immutable
data class SchoolHitUi(
    val regionKey: String?,
    val nameRu: String?,
    val nameEn: String?,
    val label: String?,
    val schools: Int,
    val examples: List<String>,
)

@Immutable
data class RegionSearchUi(
    val query: String = "",
    val rows: List<RegionRowUi> = emptyList(),
    val school: SchoolAnswer = SchoolAnswer.NotAsked,
    val searching: Boolean = false,
)

/**
 * The region step's search, in two speeds.
 *
 * Every keystroke filters the bundled catalog — names, aliases, cities, codes —
 * which costs no request. The server's school directory is asked only once the
 * typing has paused for [debounceMillis], or on the keyboard's search key: each
 * call is counted against this phone and spent from an allowance the bot's own
 * class search needs too, so never one per letter. Whether a name is worth
 * asking about at all is `RegionLookup`'s rule, not this one's.
 */
class RegionFinder(
    private val scope: CoroutineScope,
    private val catalog: suspend () -> RegionCatalog,
    private val byName: suspend (String) -> List<RegionMatch>,
    private val find: suspend (String) -> RegionLookupResult,
    private val debounceMillis: Long = SchoolDirectory.DEBOUNCE_MILLIS,
) {
    private val mutable = MutableStateFlow(RegionSearchUi())
    val state: StateFlow<RegionSearchUi> = mutable.asStateFlow()

    private var nameJob: Job? = null
    private var schoolJob: Job? = null

    fun type(query: String) {
        mutable.update { it.copy(query = query, school = SchoolAnswer.NotAsked, searching = false) }
        nameJob?.cancel()
        nameJob = scope.launch {
            val book = catalog()
            val found = runCatching { byName(query) }.getOrDefault(emptyList())
            mutable.update { it.copy(rows = found.map { match -> regionRowOf(book, match) }) }
        }
        schoolJob?.cancel()
        if (worthAsking(query)) {
            schoolJob = scope.launch {
                delay(debounceMillis)
                lookUp(query)
            }
        }
    }

    /** The keyboard's search key: ask now rather than after the pause. */
    fun submit() {
        val query = mutable.value.query
        if (!worthAsking(query)) return
        schoolJob?.cancel()
        schoolJob = scope.launch { lookUp(query) }
    }

    private fun worthAsking(query: String) = query.trim().length >= SchoolDirectory.MIN_QUERY_LENGTH

    private suspend fun lookUp(query: String) {
        mutable.update { it.copy(searching = true) }
        val book = catalog()
        val result = runCatching { find(query) }.getOrNull()
        mutable.update { current ->
            if (current.query != query) return@update current
            current.copy(
                searching = false,
                rows = result?.byName?.map { match -> regionRowOf(book, match) } ?: current.rows,
                school = result?.bySchool?.let(::answerOf) ?: SchoolAnswer.Failed(DirectoryFailure.OFF),
            )
        }
    }
}

/** The directory's answer as the step draws it. */
fun answerOf(lookup: SchoolLookup): SchoolAnswer = when (lookup) {
    SchoolLookup.NotAsked -> SchoolAnswer.NotAsked
    SchoolLookup.Generic -> SchoolAnswer.Generic
    is SchoolLookup.Found -> SchoolAnswer.Found(
        hits = lookup.hits.hits.map { hit ->
            SchoolHitUi(
                regionKey = hit.region?.key,
                nameRu = hit.region?.nameRu,
                nameEn = hit.region?.nameEn,
                label = hit.label,
                schools = hit.schools,
                examples = hit.examples,
            )
        },
        truncated = lookup.hits.truncated,
    )
    is SchoolLookup.Failed -> when (val problem = lookup.problem) {
        is DirectoryProblem.Throttled -> SchoolAnswer.Failed(DirectoryFailure.WAIT, minutesOf(problem.retryAfterSeconds))
        is DirectoryProblem.Spent -> SchoolAnswer.Failed(DirectoryFailure.WAIT, minutesOf(problem.retryAfterSeconds))
        DirectoryProblem.ServerMissing -> SchoolAnswer.Failed(DirectoryFailure.NO_SERVER)
        DirectoryProblem.Offline -> SchoolAnswer.Failed(DirectoryFailure.OFFLINE)
        else -> SchoolAnswer.Failed(DirectoryFailure.OFF)
    }
}

/** Rounded up, never zero: «через 0 минут» is an instruction to do nothing. */
private fun minutesOf(seconds: Long?): Int? = seconds?.let { ((it + 59) / 60).toInt().coerceAtLeast(1) }

/** A saved phase name read back, or `null` for one this build does not have. */
internal fun phaseOf(name: String?): DiaryImportPhase? = DiaryImportPhase.entries.firstOrNull { it.name == name }

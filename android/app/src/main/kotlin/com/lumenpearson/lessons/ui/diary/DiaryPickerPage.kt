package com.lumenpearson.lessons.ui.diary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.catalog.CatalogRegion
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.core.data.upstream.DiarySchoolSearch
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.ui.common.openInBrowser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * «Which diary?», answered as region, then school: the page a phone that knows
 * no diary yet uses to say which one to sign in to.
 *
 * Settings → «Дневник» opens it on a phone in a class whose join named no
 * diary (r11, gap 2); the onboarding's own region and school steps are richer
 * — the smart search, the «почему» of a recommendation — and may host this
 * page or build on its view model. Either way the rule for what a region
 * offers is [diaryChoiceOf]: the catalog's own answer, never a second
 * derivation of it.
 *
 * It asks nobody anything but the region's own diary: regions are searched in
 * the bundled catalog, and schools straight from the phone to the region's
 * server ([DiarySchoolSearch]), which is the server the password will go to
 * next and so the honest early warning that it cannot be reached.
 *
 * @param onPicked the diary to sign in to, with a blank login — the form that
 *   follows ([DiaryCredentialFields]) is where the login is typed.
 * @param onCancel back from the region list; back from the school step returns
 *   to the regions instead.
 */
@Composable
fun DiaryPickerPage(
    onPicked: (DiaryTarget) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiaryPickerViewModel = viewModel(factory = DiaryPickerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)

    // The picker's answer arrives as state rather than as a return value, so a
    // Petersburg tap — which needs no second step — and a school tap reach the
    // caller the same way, once.
    LaunchedEffect(state.picked) {
        val target = state.picked ?: return@LaunchedEffect
        viewModel.consumePicked()
        picked(target)
    }

    BackHandler {
        if (state.region != null) viewModel.clearRegion() else onCancel()
    }

    val listState = rememberLazyListState()
    ReportScrollOffset(listState)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .appScrollMotionBlur(listState),
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = statusBarSpace() + 8.dp,
                bottom = LocalBottomBarSpace.current,
            ),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
        ) {
            item(key = "header") {
                ScreenHeader(
                    title = correctedString(R.string.diary_pick_title),
                    subtitle = correctedString(R.string.diary_pick_subtitle),
                )
            }

            val region = state.region
            if (region == null) {
                item(key = "region-search") {
                    SearchField(
                        value = state.regionQuery,
                        onValueChange = viewModel::searchRegions,
                        label = correctedString(R.string.diary_pick_region_search),
                    )
                }
                if (state.regions.isEmpty() && state.regionQuery.isNotBlank()) {
                    item(key = "regions-empty") {
                        PickerNote(correctedString(R.string.diary_pick_regions_empty))
                    }
                } else {
                    item(key = "regions") {
                        RoundedCardContainer {
                            state.regions.forEach { row ->
                                GroupItem(
                                    title = localized(row.nameRu, row.nameEn).orEmpty(),
                                    subtitle = row.code.takeIf { it.isNotBlank() },
                                    icon = Icons.Rounded.Map,
                                    tone = accentTone(0),
                                    onClick = { viewModel.pickRegion(row) },
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "region") {
                    RoundedCardContainer {
                        GroupLinkItem(
                            title = localized(region.nameRu, region.nameEn).orEmpty(),
                            icon = Icons.Rounded.Map,
                            tone = accentTone(0),
                            value = correctedString(R.string.diary_pick_change),
                            onClick = viewModel::clearRegion,
                        )
                    }
                }
                when (val choice = region.choice) {
                    // Picked the moment the region was; nothing more to draw.
                    DiaryRegionChoice.Petersburg -> Unit
                    is DiaryRegionChoice.NetSchool -> schoolStep(state, viewModel)
                    is DiaryRegionChoice.Elsewhere -> item(key = "elsewhere") {
                        RoundedCardContainer {
                            GroupRow {
                                Text(
                                    text = correctedString(
                                        if (choice.url != null) {
                                            R.string.diary_pick_handoff
                                        } else {
                                            R.string.diary_pick_unsupported
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Always the browser, never an app named by
                            // package: which app takes the link is the
                            // phone's decision, and nothing comes back.
                            choice.url?.let { url ->
                                GroupActionItem(
                                    label = correctedString(R.string.diary_pick_open_site),
                                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    onClick = { openInBrowser(context, url) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.schoolStep(
    state: DiaryPickerState,
    viewModel: DiaryPickerViewModel,
) {
    item(key = "school-search") {
        SearchField(
            value = state.schoolQuery,
            onValueChange = viewModel::searchSchools,
            label = correctedString(R.string.diary_pick_school_search),
        )
    }
    when {
        state.searching -> item(key = "schools-loading") { SkeletonGroup(rows = 3) }

        state.problem != null -> item(key = "schools-problem") {
            EmptyState(
                title = correctedString(R.string.diary_failure_title),
                description = state.problem.asText(),
                actionLabel = correctedString(R.string.diary_retry).takeIf { state.problem.offersRetry },
                onActionClick = viewModel::retrySchools.takeIf { state.problem.offersRetry },
            )
        }

        state.schoolQuery.trim().length < DiarySchoolSearch.MIN_QUERY_LENGTH -> item(key = "schools-hint") {
            PickerNote(correctedString(R.string.diary_pick_school_hint))
        }

        state.schools.isEmpty() -> item(key = "schools-empty") {
            PickerNote(correctedString(R.string.diary_pick_schools_empty))
        }

        else -> item(key = "schools") {
            RoundedCardContainer {
                state.schools.forEach { school ->
                    GroupItem(
                        title = school.name,
                        subtitle = school.address,
                        icon = Icons.Rounded.School,
                        tone = accentTone(1),
                        onClick = { viewModel.pickSchool(school) },
                    )
                }
            }
        }
    }
}

/** A sentence in place of a list: nothing found, or what to type first. */
@Composable
private fun PickerNote(text: String) {
    RoundedCardContainer {
        GroupRow {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/** A region as the picker draws it; see [DiaryPlace] for why not the catalog's type. */
@Immutable
data class DiaryRegionRow(
    val key: String,
    val nameRu: String,
    val nameEn: String,
    val code: String,
    val choice: DiaryRegionChoice,
)

/** A school as the picker draws it. */
@Immutable
data class DiarySchoolRow(val id: Long, val name: String, val address: String?)

/**
 * @property picked the answer, waiting to be handed to the caller once.
 * @property problem why the school search failed — `DiaryProblemText`'s to say,
 *   since it is the same region server the sign-in will talk to.
 */
data class DiaryPickerState(
    val regionQuery: String = "",
    val regions: List<DiaryRegionRow> = emptyList(),
    val region: DiaryRegionRow? = null,
    val schoolQuery: String = "",
    val schools: List<DiarySchoolRow> = emptyList(),
    val searching: Boolean = false,
    val problem: DiarySignInProblem? = null,
    val picked: DiaryTarget? = null,
)

/**
 * The picker's state holder.
 *
 * @param regions the catalog's regions for a query, best first; every region
 *   in order for a blank one. No request is behind it.
 * @param schools the region server's schools for a query — `DiarySchoolSearch`.
 */
class DiaryPickerViewModel(
    private val regions: suspend (query: String) -> List<CatalogRegion>,
    private val schools: suspend (regionKey: String, query: String) -> Result<List<DiarySchool>>,
) : ViewModel() {

    private val mutable = MutableStateFlow(DiaryPickerState())
    val state: StateFlow<DiaryPickerState> = mutable.asStateFlow()

    private var regionJob: Job? = null
    private var schoolJob: Job? = null

    init {
        searchRegions("")
    }

    fun searchRegions(query: String) {
        mutable.update { it.copy(regionQuery = query) }
        regionJob?.cancel()
        regionJob = viewModelScope.launch {
            val found = runCatching { regions(query) }.getOrDefault(emptyList())
            mutable.update { it.copy(regions = found.map(::rowOf)) }
        }
    }

    /**
     * A region tapped. Petersburg is answered at once — its account decides the
     * school, so there is nothing more to ask; a «Сетевой город» region goes on
     * to its schools; anywhere else stays on the page to say why not.
     */
    fun pickRegion(row: DiaryRegionRow) {
        schoolJob?.cancel()
        mutable.update {
            it.copy(region = row, schoolQuery = "", schools = emptyList(), searching = false, problem = null)
        }
        if (row.choice == DiaryRegionChoice.Petersburg) {
            mutable.update { it.copy(picked = DiaryTarget.petersburg(login = "")) }
        }
    }

    fun clearRegion() {
        schoolJob?.cancel()
        mutable.update {
            it.copy(region = null, schoolQuery = "", schools = emptyList(), searching = false, problem = null)
        }
    }

    /**
     * Asks the region's server after the typing stops
     * ([DiarySchoolSearch.DEBOUNCE_MILLIS]), so it sees one person typing and
     * not one request per letter.
     */
    fun searchSchools(query: String) {
        mutable.update { it.copy(schoolQuery = query) }
        search(query, debounce = true)
    }

    fun retrySchools() = search(mutable.value.schoolQuery, debounce = false)

    private fun search(query: String, debounce: Boolean) {
        val choice = mutable.value.region?.choice as? DiaryRegionChoice.NetSchool ?: return
        schoolJob?.cancel()
        if (query.trim().length < DiarySchoolSearch.MIN_QUERY_LENGTH) {
            mutable.update { it.copy(schools = emptyList(), searching = false, problem = null) }
            return
        }
        schoolJob = viewModelScope.launch {
            if (debounce) delay(DiarySchoolSearch.DEBOUNCE_MILLIS)
            mutable.update { it.copy(searching = true, problem = null) }
            schools(choice.regionKey, query).fold(
                onSuccess = { found ->
                    mutable.update {
                        it.copy(
                            searching = false,
                            schools = found.map { school -> DiarySchoolRow(school.id, school.name, school.address) },
                        )
                    }
                },
                onFailure = { failure ->
                    mutable.update {
                        it.copy(searching = false, schools = emptyList(), problem = DiarySignInProblem.of(failure))
                    }
                },
            )
        }
    }

    fun pickSchool(school: DiarySchoolRow) {
        val region = mutable.value.region ?: return
        val choice = region.choice as? DiaryRegionChoice.NetSchool ?: return
        mutable.update {
            it.copy(
                picked = DiaryTarget.netschool(
                    region = choice.regionKey,
                    schoolId = school.id,
                    schoolName = school.name,
                    login = "",
                    zone = choice.zone,
                ),
            )
        }
    }

    /** The caller has the answer; the next visit starts at the regions. */
    fun consumePicked() {
        mutable.update { DiaryPickerState(regionQuery = "", regions = it.regions) }
        searchRegions("")
    }

    private fun rowOf(region: CatalogRegion) = DiaryRegionRow(
        key = region.key,
        nameRu = region.nameRu,
        nameEn = region.nameEn,
        code = region.code,
        choice = diaryChoiceOf(region),
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = Graph.container
                DiaryPickerViewModel(
                    regions = { query -> container.regionLookup.byName(query).map { it.region } },
                    schools = { region, query -> container.diarySchoolSearch.search(region, query) },
                )
            }
        }
    }
}

package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.catalog.RegionLookupResult
import com.lumenpearson.lessons.core.data.catalog.RegionMatch
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.diary.DiaryCache
import com.lumenpearson.lessons.core.data.diary.DiaryImport
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.ui.diary.DiaryPlace
import com.lumenpearson.lessons.ui.diary.DiarySchoolRow
import com.lumenpearson.lessons.ui.diary.diaryPlaceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the flow reaches in the rest of the app, as functions a test can
 * replace one by one. `RegionLookup` has no public constructor and the diary's
 * session type none either, which is why these are lambdas and adapters rather
 * than the container's own objects.
 */
class OnboardingDeps(
    val settings: SettingsRepository,
    val shellMode: ShellModeSource,
    val currentClass: suspend () -> Session?,
    val currentDiary: suspend () -> DiarySession?,
    val storedTarget: suspend () -> DiaryTarget?,
    val signOutDiary: suspend () -> Result<Unit>,
    val signIn: OnboardingSignIn,
    val diaryImport: DiaryImport,
    val cache: DiaryCache,
    val catalog: suspend () -> RegionCatalog,
    val regionsByName: suspend (String) -> List<RegionMatch>,
    val findRegions: suspend (String) -> RegionLookupResult,
    val schools: suspend (regionKey: String, query: String) -> Result<List<DiarySchool>>,
)

/**
 * The sign-in step's heading: which diary, and where the password goes.
 *
 * @property forgotUrl the diary's own site, for «Забыли пароль?» — https only.
 */
@Immutable
data class SignInHeader(
    val provider: DiaryProviderKey,
    val place: DiaryPlace,
    val schoolName: String?,
    val classBound: Boolean,
    val className: String?,
    val forgotUrl: String?,
)

/**
 * The last screen: who the diary is, the class if there is one, and what the
 * session costs to keep.
 */
@Immutable
data class SummaryUi(
    val studentName: String?,
    val studentClass: String?,
    val studentSchool: String?,
    val place: DiaryPlace?,
    val login: String?,
    val petersburg: Boolean,
    val className: String?,
    val classSchool: String?,
) {
    /** A class is joined: the timetable, the widget and the alerts are the class's. */
    val inClass: Boolean get() = className != null
}

/**
 * The first run's state holder.
 *
 * Resolved against the activity's store, like every view model here — which
 * means it outlives the flow: [finish] clears what it saved and marks it done,
 * and [start] builds a fresh flow the next time the way in is shown (leaving
 * the last class) instead of reopening the summary of the last one.
 *
 * What survives what:
 *  * the path and the answers ([OnboardingState]) are in [saved], so a
 *    recreation below API 33 (a language change) and a process death both
 *    bring the flow back where it was;
 *  * the login is in [SignInStep]'s memory: recreation yes, process death no;
 *  * the password is in the form's `remember` and nowhere here;
 *  * the diary's session between the phone's sign-in and our server's answer
 *    is a plain field of [SignInStep], dropped when the step is left.
 *
 * The hold (K3): entering the class-code or the sign-in step asks the shell to
 * keep the flow on screen, because the join and the registration each write a
 * credential that would otherwise swap the flow for a home mid-way; [finish]
 * releases it. After a process death with no saved state, [start] resumes by
 * [OnboardingFlow.resume].
 */
class OnboardingViewModel(
    private val saved: SavedStateHandle,
    private val deps: OnboardingDeps,
) : ViewModel() {

    private val mutable = MutableStateFlow<OnboardingState?>(null)
    val flow: StateFlow<OnboardingState?> = mutable.asStateFlow()

    private val catalogState = MutableStateFlow<RegionCatalog?>(null)

    val region = RegionFinder(
        scope = viewModelScope,
        catalog = ::catalog,
        byName = deps.regionsByName,
        find = deps.findRegions,
    )
    val school = SchoolFinder(scope = viewModelScope, schools = deps.schools)
    val signIn = SignInStep(scope = viewModelScope, signIn = deps.signIn)
    val importing = ImportRunner(scope = viewModelScope, import = deps.diaryImport)

    val baseUrl: StateFlow<String> = deps.settings.settings
        .map { it.baseUrl }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** What the chosen region makes of the rest of its chapter; `null` until one is chosen. */
    val plan: StateFlow<RegionPlan?> = combine(catalogState, mutable) { catalog, state ->
        catalog?.region(state?.choices?.region)?.let(::regionPlanOf)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val provider: StateFlow<ProviderPage?> = combine(catalogState, mutable) { catalog, state ->
        val choices = state?.choices ?: return@combine null
        catalog?.region(choices.region)?.let { providerPageOf(catalog, it, choices.school) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val header = MutableStateFlow<SignInHeader?>(null)
    val signInHeader: StateFlow<SignInHeader?> = header.asStateFlow()

    private val summaryState = MutableStateFlow<SummaryUi?>(null)
    val summary: StateFlow<SummaryUi?> = summaryState.asStateFlow()

    /** The diary the sign-in step signs in to; resolved on entering it, never saved. */
    private var target: DiaryTarget? = null

    private var finished = false

    /** Bumped by every [start], so arriving at the same step in a new flow still counts as arriving. */
    private var generation = 0

    init {
        viewModelScope.launch { catalogState.value = deps.catalog() }
        viewModelScope.launch {
            mutable.filterNotNull()
                .map { generation to it.current }
                .distinctUntilChanged()
                .collect { (_, step) -> entered(step) }
        }
    }

    /**
     * Builds the flow the first time the way in is shown — from what was
     * saved, else from the stored hold (a flow the process lost mid-way), else
     * from the start. Called by the screen whenever it enters the composition.
     */
    fun start(introduced: Boolean) {
        if (mutable.value != null && !finished) return
        finished = false
        generation += 1
        mutable.value = null
        onboardingStateOf(OnboardingKeys.all.associateWith { saved.get<Any?>(it) })?.let {
            mutable.value = it
            return
        }
        viewModelScope.launch {
            val shell = deps.shellMode.current()
            if (!shell.held || shell.mode == ShellMode.NONE) {
                set(OnboardingFlow.initial(introduced))
                return@launch
            }
            val resumed = OnboardingFlow.resume(
                mode = shell.mode,
                bindingUsable = classTargetOf(catalog(), deps.currentClass()?.diary) != null,
                hasDiarySession = deps.currentDiary() != null,
            )
            if (resumed == null) finish() else set(resumed)
        }
    }

    // --- The introduction and the chooser -------------------------------------------------

    /** The introduction's own «next»: the steps run in order up to the chooser. */
    fun next() {
        val state = mutable.value ?: return
        val following = when (state.current) {
            OnboardingStep.WELCOME -> OnboardingStep.ACKNOWLEDGEMENT
            OnboardingStep.ACKNOWLEDGEMENT -> OnboardingStep.PREFERENCES
            OnboardingStep.PREFERENCES -> OnboardingStep.PERMISSIONS
            OnboardingStep.PERMISSIONS -> OnboardingStep.WAY_IN
            else -> return
        }
        set(OnboardingFlow.push(state, following))
    }

    fun chooseWay(way: WayIn) = update { it.copy(choices = it.choices.copy(wayIn = way)) }

    fun proceedFromWayIn() {
        val state = mutable.value ?: return
        when (state.choices.wayIn) {
            WayIn.CLASS_CODE -> set(OnboardingFlow.push(state, OnboardingStep.CLASS_CODE))
            WayIn.FIND_SCHOOL -> set(OnboardingFlow.push(state, OnboardingStep.REGION))
            null -> Unit
        }
    }

    fun setServer(url: String) {
        viewModelScope.launch { deps.settings.update { it.copy(baseUrl = url) } }
    }

    fun back() {
        val state = mutable.value ?: return
        if (!state.canGoBack) return
        if (state.current == OnboardingStep.SIGN_IN) signIn.abandon()
        set(OnboardingFlow.back(state))
    }

    // --- Region, school, provider ---------------------------------------------------------

    /**
     * A region picked; [hint] is the school name typed, when the region was
     * reached through the directory. Picking another region forgets the school
     * and the system picked for the last one.
     */
    fun pickRegion(key: String, hint: String? = null) = update {
        if (it.choices.region == key && hint == null) {
            it
        } else {
            it.copy(choices = it.choices.copy(region = key, schoolHint = hint, school = null, system = null))
        }
    }

    fun proceedFromRegion() {
        val state = mutable.value ?: return
        if (state.choices.region == null) return
        val next = if (plan.value?.showsSchool == true) OnboardingStep.SCHOOL else OnboardingStep.PROVIDER
        set(OnboardingFlow.push(state, next))
    }

    fun pickSchool(row: DiarySchoolRow) = update {
        it.copy(choices = it.choices.copy(school = PickedSchool(row.id, row.name)))
    }

    fun proceedFromSchool() {
        val state = mutable.value ?: return
        set(OnboardingFlow.push(state, OnboardingStep.PROVIDER))
    }

    /** «Моей школы нет в списке»: on to the provider step, where the class code is. */
    fun skipSchool() {
        val state = mutable.value ?: return
        set(OnboardingFlow.push(state.copy(choices = state.choices.copy(school = null)), OnboardingStep.PROVIDER))
    }

    /** A «Сетевой город» row with no school: back to the school step, which is behind this one. */
    fun toSchool() {
        val state = mutable.value ?: return
        val before = state.path.getOrNull(state.depth - 1)
        set(if (before == OnboardingStep.SCHOOL) OnboardingFlow.back(state) else OnboardingFlow.push(state, OnboardingStep.SCHOOL))
    }

    /**
     * The system to sign in to, from the provider step. The server address is
     * the screen's gate, before this is called: the registration needs one.
     */
    fun signInWith(systemIndex: Int) {
        val state = mutable.value ?: return
        val withSystem = state.copy(choices = state.choices.copy(system = systemIndex, classBound = false))
        set(OnboardingFlow.push(withSystem, OnboardingStep.SIGN_IN))
    }

    fun toClassCode() {
        val state = mutable.value ?: return
        set(OnboardingFlow.push(state, OnboardingStep.CLASS_CODE))
    }

    // --- The class code -------------------------------------------------------------------

    /**
     * A class joined. When the join named a diary this build can sign in to,
     * and no diary is signed in on this phone already, the flow offers it —
     * committed, so the join screen cannot be walked back into over a class
     * already joined. Otherwise the flow is done.
     */
    fun onJoined() {
        viewModelScope.launch {
            val state = mutable.value ?: return@launch
            val usable = classTargetOf(catalog(), deps.currentClass()?.diary) != null
            if (usable && deps.currentDiary() == null) {
                set(
                    OnboardingFlow.commit(state, OnboardingStep.SIGN_IN)
                        .let { it.copy(choices = it.choices.copy(classBound = true)) },
                )
            } else {
                finish()
            }
        }
    }

    // --- Sign-in --------------------------------------------------------------------------

    fun submitSignIn(password: String) {
        val chosen = target ?: return
        signIn.submit(chosen, password, onRegistered = ::registered)
    }

    fun retrySignIn() = signIn.retry(onRegistered = ::registered)

    /** «Не сейчас» on a class's diary: the class is joined, and the diary can wait for settings. */
    fun skipSignIn() {
        signIn.abandon()
        finish()
    }

    /**
     * The server holds the session: committed, so a back gesture cannot reopen
     * the form and mint a second server session with a second sign-in.
     */
    private fun registered() {
        val state = mutable.value ?: return
        set(OnboardingFlow.commit(state, OnboardingStep.IMPORT))
    }

    // --- Import ---------------------------------------------------------------------------

    fun retryImport() {
        val failed = importing.state.value.failed ?: return
        importing.stop()
        importing.start(resumeFrom = failed.phase.takeIf { failed.resumable })
    }

    /**
     * The diary ended the session during the import: back to the form, and
     * pick up where the import stopped once a new session is registered.
     */
    fun signInAgain() {
        val state = mutable.value ?: return
        val failed = importing.state.value.failed
        importing.stop()
        val reopened = OnboardingFlow.reopenSignIn(state)
        set(reopened.copy(choices = reopened.choices.copy(resumeFrom = failed?.phase?.name)))
    }

    /**
     * «Выйти из дневника и начать заново»: best-effort sign-out, then the
     * chooser — or, on a class's diary, the end of the flow, since the class is
     * still there to go home to.
     */
    fun startOver() {
        viewModelScope.launch {
            importing.stop()
            deps.signOutDiary()
            val state = mutable.value ?: return@launch
            if (state.choices.classBound || deps.currentClass() != null) finish() else set(OnboardingFlow.restart())
        }
    }

    fun pickStudent(id: Long) = importing.choose(id)

    /** The import finished and its full bar has been seen. */
    fun importShown() {
        val state = mutable.value ?: return
        if (state.current != OnboardingStep.IMPORT || importing.state.value.done == null) return
        set(OnboardingFlow.commit(state, OnboardingStep.SUMMARY))
    }

    // --- The end --------------------------------------------------------------------------

    /**
     * The flow is over: what it saved goes, the hold is released, and the
     * shell swaps the flow for the home the stored credential asks for.
     */
    fun finish() {
        finished = true
        signIn.abandon()
        importing.stop()
        OnboardingKeys.all.forEach { saved.remove<Any?>(it) }
        viewModelScope.launch { deps.shellMode.release() }
    }

    // --- Internals ------------------------------------------------------------------------

    private suspend fun catalog(): RegionCatalog =
        catalogState.value ?: deps.catalog().also { catalogState.value = it }

    private fun update(transform: (OnboardingState) -> OnboardingState) {
        mutable.value?.let { set(transform(it)) }
    }

    private fun set(state: OnboardingState) {
        mutable.value = state
        if (finished) return
        state.toSaved().forEach { (key, value) -> saved[key] = value }
    }

    /** Side effects of arriving at a step, once per arrival. */
    private suspend fun entered(step: OnboardingStep) {
        when (step) {
            // Reaching the chooser is what counts as having seen the
            // introduction: somebody who closes the app here is not made to
            // read it again.
            OnboardingStep.WAY_IN -> deps.settings.update {
                if (it.onboardingDone) it else it.copy(onboardingDone = true)
            }
            OnboardingStep.CLASS_CODE -> deps.shellMode.hold()
            OnboardingStep.REGION -> if (region.state.value.rows.isEmpty()) region.type(region.state.value.query)
            OnboardingStep.SCHOOL -> {
                val choices = mutable.value?.choices ?: return
                val key = catalog().region(choices.region)
                    ?.systems?.firstNotNullOfOrNull { it.netschool?.takeIf { row -> row.password }?.region }
                    ?: return
                school.open(key, schoolQueryFromHint(choices.schoolHint, catalog().search.schoolWords))
            }
            OnboardingStep.SIGN_IN -> {
                deps.shellMode.hold()
                resolveTarget()
            }
            OnboardingStep.IMPORT -> {
                val choices = mutable.value?.choices
                if (!importing.running && importing.state.value.done == null) {
                    importing.start(resumeFrom = phaseOf(choices?.resumeFrom))
                }
                if (choices?.resumeFrom != null) update { it.copy(choices = it.choices.copy(resumeFrom = null)) }
            }
            OnboardingStep.SUMMARY -> loadSummary()
            else -> Unit
        }
    }

    /**
     * The diary the form signs in to: the one picked on the provider step, else
     * the joined class's, else the one this phone was signed in to (a re-sign-in
     * after the diary ended an import).
     */
    private suspend fun resolveTarget() {
        val choices = mutable.value?.choices ?: return
        val catalog = catalog()
        val cls = deps.currentClass()
        val picked = catalog.region(choices.region)?.let { region ->
            choices.system?.let(region.systems::getOrNull)?.let { targetFor(region, it, choices.school) }
        }
        val chosen = when {
            choices.classBound -> classTargetOf(catalog, cls?.diary)
            else -> picked
        } ?: deps.storedTarget()
        target = chosen
        if (chosen == null) {
            header.value = null
            return
        }
        val stored = deps.storedTarget()
        if (signIn.state.value.login.isBlank() && stored?.provider == chosen.provider) {
            signIn.setLogin(stored.login)
        }
        val system = catalog.region(choices.region)?.let { region -> choices.system?.let(region.systems::getOrNull) }
        header.value = SignInHeader(
            provider = chosen.provider,
            place = diaryPlaceOf(catalog, chosen),
            schoolName = chosen.schoolName,
            classBound = choices.classBound,
            className = if (choices.classBound) cls?.className else null,
            forgotUrl = (system?.webUrl ?: forgotUrlOf(catalog, chosen))?.takeIf { it.startsWith("https://") },
        )
    }

    private fun forgotUrlOf(catalog: RegionCatalog, target: DiaryTarget): String? =
        catalog.regions.asSequence().flatMap { it.systems.asSequence() }.firstOrNull { system ->
            when (target.provider) {
                DiaryProviderKey.PETERSBURG -> system.platform == "petersburg"
                DiaryProviderKey.NETSCHOOL -> system.netschool?.region == target.region
            }
        }?.webUrl

    private suspend fun loadSummary() {
        val catalog = catalog()
        val session = deps.currentDiary()
        val cls = deps.currentClass()
        val pupils = deps.cache.students.first().students
        val selected = deps.cache.selectedStudentId.first()
        val student = pupils.firstOrNull { it.id == selected } ?: pupils.firstOrNull()
        summaryState.value = SummaryUi(
            studentName = student?.fullName?.ifBlank { student.shortName },
            studentClass = student?.className,
            studentSchool = student?.school ?: session?.target?.schoolName,
            place = session?.target?.let { diaryPlaceOf(catalog, it) },
            login = session?.login,
            petersburg = session?.target?.provider == DiaryProviderKey.PETERSBURG,
            className = cls?.className,
            classSchool = cls?.school,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = Graph.container
                OnboardingViewModel(
                    saved = createSavedStateHandle(),
                    deps = OnboardingDeps(
                        settings = container.settingsRepository,
                        shellMode = container.shellMode,
                        currentClass = { container.sessionRepository.current() },
                        currentDiary = { container.diaryRepository.current() },
                        storedTarget = { container.diaryRepository.target.first() },
                        signOutDiary = { container.diaryRepository.signOut() },
                        signIn = container.diarySignIn.forOnboarding(),
                        diaryImport = container.diaryImport,
                        cache = container.diaryCache,
                        catalog = { container.regionLookup.catalog() },
                        regionsByName = { container.regionLookup.byName(it) },
                        findRegions = { container.regionLookup.find(it) },
                        schools = { region, query -> container.schoolDirectory.providerSchools(region, query) },
                    ),
                )
            }
        }
    }
}

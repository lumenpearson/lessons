package com.lumenpearson.lessons.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.catalog.RegionLookupResult
import com.lumenpearson.lessons.core.data.catalog.RegionSearch
import com.lumenpearson.lessons.core.data.catalog.SchoolLookup
import com.lumenpearson.lessons.core.data.diary.DiaryImport
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryRegistration
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.data.repository.SyncArming
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.ui.diary.FakeDiaryCache
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/** The catalog the APK carries, read from the source tree rather than a copy that could drift. */
internal val realCatalog: RegionCatalog by lazy {
    var directory: File? = File("").absoluteFile
    while (directory != null) {
        val file = File(directory, "server/app/catalog/data/regions.json")
        if (file.isFile) return@lazy RegionCatalog.parse(file.readText())
        directory = directory.parentFile
    }
    error("regions.json not found from ${File("").absolutePath}")
}

internal val realSearch: RegionSearch by lazy { RegionSearch(realCatalog) }

internal fun region(key: String) = checkNotNull(realCatalog.region(key)) { "no region $key" }

internal class FakeShellMode(initial: ShellState = ShellState(ShellMode.NONE, held = false)) : ShellModeSource {
    val stored = MutableStateFlow(initial)
    override val state: Flow<ShellState> = stored
    override suspend fun current(): ShellState = stored.value
    override val syncArming: Flow<SyncArming> = emptyFlow()
    override suspend fun hold() {
        stored.value = stored.value.copy(held = true)
    }
    override suspend fun release() {
        stored.value = stored.value.copy(held = false)
    }
    override suspend fun settleColdStart(): ShellState = stored.value
}

internal class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    val stored = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = stored
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        stored.value = transform(stored.value)
    }
    override fun languageBlocking(): AppLanguage = stored.value.language
}

/** A diary session the fake sign-in hands over; nothing in it is a secret. */
internal class TestHeld(override val target: DiaryTarget) : HeldUpstream

/**
 * The sign-in, scripted: what each step answers, and every call it was asked.
 * The password is recorded only to prove, elsewhere, that nothing else kept it.
 */
internal class FakeSignIn : OnboardingSignIn {
    var preflightResult: Result<Unit> = Result.success(Unit)
    var openResult: (DiaryTarget) -> Result<HeldUpstream> = { Result.success(TestHeld(it)) }
    val registerResults = ArrayDeque<Result<DiaryRegistration>>()
    val calls = mutableListOf<String>()
    val opened = mutableListOf<Pair<DiaryTarget, String>>()
    val registered = mutableListOf<HeldUpstream>()
    val discarded = mutableListOf<HeldUpstream>()

    override suspend fun preflight(target: DiaryTarget): Result<Unit> {
        calls += "preflight"
        return preflightResult
    }

    override suspend fun open(target: DiaryTarget, password: String): Result<HeldUpstream> {
        calls += "open"
        opened += target to password
        return openResult(target)
    }

    override suspend fun register(held: HeldUpstream): Result<DiaryRegistration> {
        calls += "register"
        registered += held
        return registerResults.removeFirstOrNull() ?: Result.success(registration(held.target))
    }

    override suspend fun discard(held: HeldUpstream) {
        calls += "discard"
        discarded += held
    }
}

internal fun registration(target: DiaryTarget) =
    DiaryRegistration(DiarySession(login = target.login, token = "bearer", target = target), emptyList())

internal val pupil = DiaryStudent(
    id = 7,
    firstName = "Ivan",
    lastName = "Petrov",
    middleName = null,
    fullName = "Petrov Ivan",
    school = "School 239",
    className = "9A",
)

/** An import that answers with [steps], or waits for the chooser when [choosing] is set. */
internal class FakeImport(
    var steps: List<DiaryImportProgress> = listOf(
        DiaryImportProgress.Running(DiaryImportPhase.STUDENTS, 0f, 0.1f),
        DiaryImportProgress.Done(pupil, emptySet(), lessons = 10, homework = 3, marks = 4),
    ),
    var choosing: List<DiaryStudent>? = null,
) : DiaryImport {
    val runs = mutableListOf<DiaryImportPhase?>()

    override fun run(
        resumeFrom: DiaryImportPhase?,
        choose: suspend (List<DiaryStudent>) -> DiaryStudent,
    ): Flow<DiaryImportProgress> = flow {
        runs += resumeFrom
        choosing?.let { students ->
            emit(DiaryImportProgress.ChoosingStudent(students, 0.1f))
            val picked = choose(students)
            emit(DiaryImportProgress.Done(picked, emptySet(), 1, 1, 1))
            return@flow
        }
        steps.forEach { emit(it) }
    }

    override suspend fun refreshIfStale(): Result<Boolean> = Result.success(false)
}

/** Everything the view model reaches, as fakes a test can set before or after it starts. */
internal class OnboardingRig(
    val shell: FakeShellMode = FakeShellMode(),
    val settings: FakeSettings = FakeSettings(),
    val signIn: FakeSignIn = FakeSignIn(),
    val import: FakeImport = FakeImport(),
    val cache: FakeDiaryCache = FakeDiaryCache(),
    val saved: SavedStateHandle = SavedStateHandle(),
) {
    var classSession: Session? = null
    var diarySession: DiarySession? = null
    var storedTarget: DiaryTarget? = null
    var signedOut = 0
    val directoryAsked = mutableListOf<String>()
    var schools: List<DiarySchool> = listOf(DiarySchool(id = 239, name = "Lyceum 239", address = null))

    fun deps() = OnboardingDeps(
        settings = settings,
        shellMode = shell,
        currentClass = { classSession },
        currentDiary = { diarySession },
        storedTarget = { storedTarget },
        signOutDiary = {
            signedOut += 1
            diarySession = null
            Result.success(Unit)
        },
        signIn = signIn,
        diaryImport = import,
        cache = cache,
        catalog = { realCatalog },
        regionsByName = { realSearch.search(it) },
        findRegions = { query ->
            directoryAsked += query
            RegionLookupResult(query, realSearch.search(query), SchoolLookup.NotAsked)
        },
        schools = { _, _ -> Result.success(schools) },
    )

    fun joinedClass(binding: DiaryBinding?) {
        classSession = Session(classId = 1, className = "9A", school = "School", token = "t", diary = binding)
    }
}

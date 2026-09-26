package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.network.DiaryApi
import com.lumenpearson.lessons.core.data.network.dto.DiaryAttendanceDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryCapabilitiesDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryMarkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryResetRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionResponseDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryStudentDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySubjectDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryTeacherDto
import com.lumenpearson.lessons.core.data.repository.DiaryRegistration
import com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySessionStore
import com.lumenpearson.lessons.core.data.repository.DiarySignIn
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.upstream.MutableClock
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * `diary.db`, in memory, for the tests that need one that remembers.
 *
 * Only the abstract statements are here — the `replace…` transactions and
 * `clear` are [DiaryDao]'s own, so the tests exercise the orchestration that
 * ships rather than a second copy of it (the `InMemoryTimetableDao` rule).
 * Every statement does exactly what its SQL says and nothing more: a fake that
 * is more thorough than its query hides the defect it stands in for.
 */
internal class InMemoryDiaryDao : DiaryDao() {

    val students = mutableListOf<DiaryStudentEntity>()
    val weeks = mutableListOf<DiaryWeekEntity>()
    val periods = mutableListOf<DiaryPeriodEntity>()
    val lessons = mutableListOf<DiaryLessonEntity>()
    val homework = mutableListOf<DiaryHomeworkEntity>()
    val marks = mutableListOf<DiaryMarkEntity>()
    private var nextRow = 1L

    private val revision = MutableStateFlow(0)

    private fun touch() {
        revision.value += 1
    }

    /** Every row of every table, for «is it empty». */
    val rowCount: Int get() = students.size + weeks.size + periods.size + lessons.size + homework.size + marks.size

    override fun observeChanges(): Flow<List<Int>> = revision.map { emptyList() }

    override suspend fun students(): List<DiaryStudentEntity> = students.sortedBy { it.position }

    override suspend fun student(studentId: Long): DiaryStudentEntity? = students.firstOrNull { it.id == studentId }

    override suspend fun week(studentId: Long, monday: LocalDate): DiaryWeekEntity? =
        weeks.firstOrNull { it.studentId == studentId && it.monday == monday }

    override suspend fun lessons(studentId: Long, from: LocalDate, to: LocalDate): List<DiaryLessonEntity> =
        lessons.filter { it.studentId == studentId && it.date in from..to }
            .sortedWith(compareBy({ it.date }, { it.position }))

    override suspend fun homework(studentId: Long, from: LocalDate, to: LocalDate): List<DiaryHomeworkEntity> =
        homework.filter { it.studentId == studentId && it.dueDate in from..to }
            .sortedWith(compareBy({ it.dueDate }, { it.position }))

    override suspend fun marks(studentId: Long): List<DiaryMarkEntity> =
        marks.filter { it.studentId == studentId }.sortedBy { it.position }

    override suspend fun periods(studentId: Long): List<DiaryPeriodEntity> =
        periods.filter { it.studentId == studentId }.sortedBy { it.position }

    override suspend fun insertStudents(rows: List<DiaryStudentEntity>) {
        rows.forEach { row ->
            students.removeAll { it.id == row.id }
            students += row
        }
        touch()
    }

    override suspend fun insertWeeksIfAbsent(rows: List<DiaryWeekEntity>) {
        rows.forEach { row ->
            if (weeks.none { it.studentId == row.studentId && it.monday == row.monday }) weeks += row
        }
        touch()
    }

    override suspend fun insertLessons(rows: List<DiaryLessonEntity>) {
        lessons += rows.map { it.copy(rowId = nextRow++) }
        touch()
    }

    override suspend fun insertHomework(rows: List<DiaryHomeworkEntity>) {
        homework += rows.map { it.copy(rowId = nextRow++) }
        touch()
    }

    override suspend fun insertMarks(rows: List<DiaryMarkEntity>) {
        marks += rows.map { it.copy(rowId = nextRow++) }
        touch()
    }

    override suspend fun insertPeriods(rows: List<DiaryPeriodEntity>) {
        rows.forEach { row ->
            check(periods.none { it.studentId == row.studentId && it.id == row.id }) { "primary key clash" }
            periods += row
        }
        touch()
    }

    override suspend fun stampLessons(studentId: Long, mondays: List<LocalDate>, at: Long) {
        weeks.replaceAll { if (it.studentId == studentId && it.monday in mondays) it.copy(lessonsSyncedAt = at) else it }
        touch()
    }

    override suspend fun stampHomework(studentId: Long, mondays: List<LocalDate>, at: Long) {
        weeks.replaceAll { if (it.studentId == studentId && it.monday in mondays) it.copy(homeworkSyncedAt = at) else it }
        touch()
    }

    override suspend fun stampMarks(studentId: Long, from: LocalDate, to: LocalDate, at: Long) {
        students.replaceAll {
            if (it.id == studentId) it.copy(marksFrom = from, marksTo = to, marksSyncedAt = at) else it
        }
        touch()
    }

    override suspend fun stampPeriods(studentId: Long, at: Long) {
        students.replaceAll { if (it.id == studentId) it.copy(periodsSyncedAt = at) else it }
        touch()
    }

    override suspend fun deleteStudentsOtherThan(keep: List<Long>) = drop { students.removeAll { it.id !in keep } }
    override suspend fun deleteWeeksOtherThan(keep: List<Long>) = drop { weeks.removeAll { it.studentId !in keep } }
    override suspend fun deletePeriodsOtherThan(keep: List<Long>) = drop { periods.removeAll { it.studentId !in keep } }
    override suspend fun deleteLessonsOtherThan(keep: List<Long>) = drop { lessons.removeAll { it.studentId !in keep } }
    override suspend fun deleteHomeworkOtherThan(keep: List<Long>) = drop { homework.removeAll { it.studentId !in keep } }
    override suspend fun deleteMarksOtherThan(keep: List<Long>) = drop { marks.removeAll { it.studentId !in keep } }

    override suspend fun deleteLessons(studentId: Long, from: LocalDate, to: LocalDate) =
        drop { lessons.removeAll { it.studentId == studentId && it.date in from..to } }

    override suspend fun deleteHomework(studentId: Long, from: LocalDate, to: LocalDate) =
        drop { homework.removeAll { it.studentId == studentId && it.dueDate in from..to } }

    override suspend fun deleteMarks(studentId: Long) = drop { marks.removeAll { it.studentId == studentId } }
    override suspend fun deletePeriods(studentId: Long) = drop { periods.removeAll { it.studentId == studentId } }

    override suspend fun deleteAllStudents() = drop { students.clear() }
    override suspend fun deleteAllWeeks() = drop { weeks.clear() }
    override suspend fun deleteAllPeriods() = drop { periods.clear() }
    override suspend fun deleteAllLessons() = drop { lessons.clear() }
    override suspend fun deleteAllHomework() = drop { homework.clear() }
    override suspend fun deleteAllMarks() = drop { marks.clear() }

    private fun drop(block: () -> Unit) {
        block()
        touch()
    }
}

/**
 * Our server's diary routes, scripted: fixed answers, an optional failure per
 * route, and a log of every request in the order it was made.
 */
internal class ScriptedDiaryApi(
    var students: List<DiaryStudentDto> = listOf(student(1)),
    var lessons: List<DiaryLessonDto> = emptyList(),
    var homework: List<DiaryHomeworkDto> = emptyList(),
    var marks: List<DiaryMarkDto> = emptyList(),
    var periods: List<DiaryPeriodDto> = emptyList(),
) : DiaryApi {

    /** `route → failure`; the route names are the ones in [calls]. */
    val failures = mutableMapOf<String, Throwable>()

    /** `students`, `periods 1`, `schedule 1 2026-09-21..2026-10-04`, … in order. */
    val calls = mutableListOf<String>()

    /** Runs inside a request, before it answers — for a test that signs out mid-read. */
    var duringRequest: suspend (String) -> Unit = {}

    private suspend fun <T> answer(route: String, value: () -> T): T {
        calls += route
        duringRequest(route)
        failures[route.substringBefore(' ')]?.let { throw it }
        return value()
    }

    override suspend fun students(): List<DiaryStudentDto> = answer("students") { students }

    override suspend fun schedule(studentId: Long, from: String, to: String): List<DiaryLessonDto> =
        answer("schedule $studentId $from..$to") { lessons }

    override suspend fun homework(studentId: Long, from: String, to: String): List<DiaryHomeworkDto> =
        answer("homework $studentId $from..$to") { homework }

    override suspend fun grades(studentId: Long, from: String, to: String): List<DiaryMarkDto> =
        answer("grades $studentId $from..$to") { marks }

    override suspend fun periods(studentId: Long): List<DiaryPeriodDto> = answer("periods $studentId") { periods }

    override suspend fun capabilities(): DiaryCapabilitiesDto = error("not used by the import")
    override suspend fun registerSession(body: DiarySessionRequestDto): DiarySessionResponseDto =
        error("not used by the import")

    override suspend fun logout() {
        calls += "logout"
        failures["logout"]?.let { throw it }
    }

    override suspend fun subjects(studentId: Long, periodId: Long?): List<DiarySubjectDto> = error("not used")

    override suspend fun teachers(studentId: Long): List<DiaryTeacherDto> = error("not used")
    override suspend fun attendance(studentId: Long): List<DiaryAttendanceDto> = error("not used")
    override suspend fun overrides(studentId: Long): List<DiaryOverrideDto> = error("not used")
    override suspend fun putOverride(studentId: Long, body: DiaryOverrideRequestDto): DiaryOverrideDto =
        error("not used")

    override suspend fun resetOverride(studentId: Long, body: DiaryResetRequestDto) = error("not used")
    override suspend fun resetOverrides(studentId: Long) = error("not used")
}

/** The diary half of the preferences, in memory. */
internal class MemoryDiaryStore(
    session: DiarySession? = DiarySession(login = "parent", token = "ours"),
    student: Long? = null,
) : DiarySessionStore {
    private val sessionFlow = MutableStateFlow(session)
    private val targetFlow = MutableStateFlow(session?.target)
    private val studentFlow = MutableStateFlow(student)

    var session: DiarySession?
        get() = sessionFlow.value
        set(value) {
            sessionFlow.value = value
            if (value != null) targetFlow.value = value.target
        }

    val student: Long? get() = studentFlow.value

    override val diarySession: Flow<DiarySession?> get() = sessionFlow
    override suspend fun currentDiarySession(): DiarySession? = sessionFlow.value
    override suspend fun writeDiarySession(value: DiarySession) {
        session = value
    }

    override suspend fun clearDiaryToken() {
        sessionFlow.value = null
    }

    override suspend fun forgetDiary() {
        sessionFlow.value = null
        targetFlow.value = null
        studentFlow.value = null
    }

    override val diaryTarget: Flow<DiaryTarget?> get() = targetFlow
    override suspend fun currentDiaryTarget(): DiaryTarget? = targetFlow.value
    override val selectedStudentId: Flow<Long?> get() = studentFlow
    override suspend fun selectStudent(id: Long?) {
        studentFlow.value = id
    }
}

/** A sign-in nothing here calls. */
internal object UnusedSignIn : DiarySignIn {
    private fun unused(): Nothing = throw DiarySignInProblem.Unexpected("not used here")
    override suspend fun preflight(target: DiaryTarget): Result<Unit> = unused()
    override suspend fun openUpstream(target: DiaryTarget, password: String): Result<UpstreamSession> = unused()
    override suspend fun register(upstream: UpstreamSession): Result<DiaryRegistration> = unused()
    override suspend fun discard(upstream: UpstreamSession) = unused()
    override suspend fun signIn(target: DiaryTarget, password: String): Result<DiaryRegistration> = unused()
}

/** The real repository and the real cache over the fakes, as the container wires them. */
internal class DiaryRig(
    val api: ScriptedDiaryApi = ScriptedDiaryApi(),
    val store: MemoryDiaryStore = MemoryDiaryStore(),
    val clock: MutableClock = MutableClock(),
) {
    val dao = InMemoryDiaryDao()
    val cache = DiaryCacheImpl(dao = { dao }, selection = store, clock = clock)
    val repository = DiaryRepositoryImpl(
        api = api,
        store = store,
        diarySignIn = UnusedSignIn,
        forgetLocal = cache::clear,
        cache = cache,
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    val import = DiaryImportImpl(repository = repository, store = store, cache = cache, clock = clock)
}

internal fun student(id: Long, first: String = "Pupil$id") =
    DiaryStudentDto(id = id, firstName = first, lastName = "Ivanova", fullName = "Ivanova $first")

internal fun lesson(date: String, subject: String = "Algebra") = DiaryLessonDto(date = date, number = 1, subject = subject)

internal fun homeworkDue(date: String, text: String = "p. 12") =
    DiaryHomeworkDto(dueDate = date, subject = "Algebra", text = text)

internal fun mark(date: String, value: String = "5") = DiaryMarkDto(subject = "Algebra", date = date, value = value, kind = "grade")

/** A status our server answers, with its headers, the way Retrofit throws it. */
internal fun statusError(code: Int, vararg headers: Pair<String, String>): HttpException {
    val request = Request.Builder().url("https://school.example/api/v1/diary/students").build()
    val raw = okhttp3.Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("error")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    return HttpException(
        Response.error<Unit>("""{"detail":"no"}""".toResponseBody("application/json".toMediaType()), raw),
    )
}

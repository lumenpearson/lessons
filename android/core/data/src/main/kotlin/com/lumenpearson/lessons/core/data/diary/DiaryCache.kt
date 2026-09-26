package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.repository.DiaryEdit
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The diary as this phone last read it, for the screens to draw before — or
 * instead of — the network.
 *
 * Read-only from here, but for which pupil is shown. The one writer of what
 * the diary said is `DiaryRepositoryImpl`: every read it
 * makes that succeeds is written through ([DiaryCacheWriter]), so the cache
 * holds exactly what the screens were last answered and never a second
 * derivation of it. The import fills it by reading through that same
 * repository, which is why the ranges it asks for are the screen's.
 *
 * Everything carries when it was read, and `null` means never — which is not
 * the same as empty: a week the diary has no lessons in is `lessonsLoadedAt`
 * set and no rows, and the screen says «уроков нет» rather than «не
 * загружено».
 *
 * Nothing in the widget, the sync worker or the alerts reads this
 * (`SyncWorkerSourceTest`): the diary is read only while somebody is looking at
 * it.
 */
interface DiaryCache {

    /** The account's pupils, in the server's order. */
    val students: Flow<DiaryCachedStudents>

    /**
     * The pupil the diary shows, when the account has several — the choice the
     * import made or the screen last stored. Kept with the session rather than
     * in the cache, because it outlives a re-sign-in to the same account; it is
     * here so a screen drawing the cache needs one object.
     */
    val selectedStudentId: Flow<Long?>

    /** Stores the pupil the diary shows; `null` forgets the choice. */
    suspend fun selectStudent(id: Long?)

    /** One Monday-to-Sunday week of one pupil; [monday] must be a Monday. */
    fun week(studentId: Long, monday: LocalDate): Flow<DiaryCachedWeek>

    /** One pupil's latest marks window. */
    fun marks(studentId: Long): Flow<DiaryCachedMarks>

    /** One pupil's terms. */
    fun periods(studentId: Long): Flow<DiaryCachedPeriods>

    /**
     * Forgets everything — signing out, or another account signing in. Also
     * makes every read already in flight land nowhere: an answer that arrives
     * after this was asked is for an account the phone has let go of.
     */
    suspend fun clear()
}

/** @see DiaryCache.students */
data class DiaryCachedStudents(
    val students: List<DiaryStudent>,
    val loadedAt: Instant?,
)

/** @see DiaryCache.week */
data class DiaryCachedWeek(
    val monday: LocalDate,
    val lessons: List<DiaryLesson>,
    val homework: List<DiaryHomework>,
    val lessonsLoadedAt: Instant?,
    val homeworkLoadedAt: Instant?,
)

/**
 * @see DiaryCache.marks
 * @property from the window [marks] covers, or `null` when none was read. The
 *   screen draws these only when it is the window it would ask for.
 */
data class DiaryCachedMarks(
    val from: LocalDate?,
    val to: LocalDate?,
    val marks: List<DiaryMark>,
    val loadedAt: Instant?,
) {
    /** Whether these are the marks of exactly [window]. */
    fun covers(window: DiaryDateRange): Boolean =
        loadedAt != null && from == window.from && to == window.to
}

/** @see DiaryCache.periods */
data class DiaryCachedPeriods(
    val periods: List<DiaryPeriod>,
    val loadedAt: Instant?,
)

/**
 * The write side of the cache, for `DiaryRepositoryImpl` alone.
 *
 * Every write carries the [generation] the read began under. [DiaryCache.clear]
 * moves the generation on, so an answer that was in flight when somebody
 * signed out — or when another account signed in — is dropped instead of
 * being written into the cache of an account that no longer owns it. Without
 * that, a sign-out during a slow week read left that week's marks and homework
 * on the phone after the screen had said they were gone.
 */
internal interface DiaryCacheWriter {

    /** Taken before a read starts; see the class note. */
    fun generation(): Long

    suspend fun putStudents(generation: Long, students: List<DiaryStudent>)

    /** [from] .. [to] must be whole weeks ([DiaryWindows.isWholeWeeks]). */
    suspend fun putLessons(generation: Long, studentId: Long, from: LocalDate, to: LocalDate, rows: List<DiaryLesson>)

    /** @see putLessons */
    suspend fun putHomework(generation: Long, studentId: Long, from: LocalDate, to: LocalDate, rows: List<DiaryHomework>)

    suspend fun putMarks(generation: Long, studentId: Long, from: LocalDate, to: LocalDate, rows: List<DiaryMark>)

    suspend fun putPeriods(generation: Long, studentId: Long, rows: List<DiaryPeriod>)

    /** For a repository built without a cache: every write goes nowhere. */
    object None : DiaryCacheWriter {
        override fun generation(): Long = 0
        override suspend fun putStudents(generation: Long, students: List<DiaryStudent>) = Unit
        override suspend fun putLessons(
            generation: Long,
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
            rows: List<DiaryLesson>,
        ) = Unit

        override suspend fun putHomework(
            generation: Long,
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
            rows: List<DiaryHomework>,
        ) = Unit

        override suspend fun putMarks(
            generation: Long,
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
            rows: List<DiaryMark>,
        ) = Unit

        override suspend fun putPeriods(generation: Long, studentId: Long, rows: List<DiaryPeriod>) = Unit
    }
}

// ---- rows <-> domain ---------------------------------------------------------

internal fun DiaryStudent.toEntity(position: Int, at: Long) = DiaryStudentEntity(
    id = id,
    firstName = firstName,
    lastName = lastName,
    middleName = middleName,
    fullName = fullName,
    school = school,
    className = className,
    position = position,
    syncedAt = at,
)

internal fun DiaryStudentEntity.toDomain() = DiaryStudent(
    id = id,
    firstName = firstName,
    lastName = lastName,
    middleName = middleName,
    fullName = fullName,
    school = school,
    className = className,
)

internal fun DiaryLesson.toEntity(studentId: Long, position: Int) = DiaryLessonEntity(
    studentId = studentId,
    date = date,
    number = number,
    subject = subject,
    startsAt = startsAt,
    endsAt = endsAt,
    room = room,
    teacher = teacher,
    homework = homework,
    topic = topic,
    target = target,
    edits = DiaryEditsCodec.encode(edits),
    ambiguous = ambiguous,
    position = position,
)

internal fun DiaryLessonEntity.toDomain() = DiaryLesson(
    date = date,
    number = number,
    subject = subject,
    startsAt = startsAt,
    endsAt = endsAt,
    room = room,
    teacher = teacher,
    homework = homework,
    topic = topic,
    target = target,
    edits = DiaryEditsCodec.decode(edits),
    ambiguous = ambiguous,
)

internal fun DiaryHomework.toEntity(studentId: Long, position: Int) = DiaryHomeworkEntity(
    studentId = studentId,
    dueDate = dueDate,
    upstreamId = id,
    subject = subject,
    text = text,
    teacher = teacher,
    target = target,
    edits = DiaryEditsCodec.encode(edits),
    ambiguous = ambiguous,
    position = position,
)

internal fun DiaryHomeworkEntity.toDomain() = DiaryHomework(
    id = upstreamId,
    dueDate = dueDate,
    subject = subject,
    text = text,
    teacher = teacher,
    target = target,
    edits = DiaryEditsCodec.decode(edits),
    ambiguous = ambiguous,
)

internal fun DiaryMark.toEntity(studentId: Long, position: Int) = DiaryMarkEntity(
    studentId = studentId,
    upstreamId = id,
    subjectId = subjectId,
    subject = subject,
    date = date,
    value = value,
    kind = kind.name,
    reason = reason,
    comment = comment,
    position = position,
)

internal fun DiaryMarkEntity.toDomain() = DiaryMark(
    id = upstreamId,
    subjectId = subjectId,
    subject = subject,
    date = date,
    value = value,
    // By name, tolerantly: a kind a later build wrote reads as OTHER here
    // rather than failing the whole marks tab.
    kind = DiaryMarkKind.fromWire(kind),
    reason = reason,
    comment = comment,
)

internal fun DiaryPeriod.toEntity(studentId: Long, position: Int) = DiaryPeriodEntity(
    studentId = studentId,
    id = id,
    name = name,
    startsOn = startsOn,
    endsOn = endsOn,
    isCurrent = isCurrent,
    position = position,
)

internal fun DiaryPeriodEntity.toDomain() = DiaryPeriod(
    id = id,
    name = name,
    startsOn = startsOn,
    endsOn = endsOn,
    isCurrent = isCurrent,
)

/**
 * A row's corrections as they are kept in one column: JSON, decoded
 * tolerantly, because they are only ever read back with their row and a
 * damaged value must cost the «исправлено» marks and not the lesson.
 */
internal object DiaryEditsCodec {

    @kotlinx.serialization.Serializable
    private data class Stored(
        val field: String = "",
        val value: String = "",
        val original: String? = null,
        val changedUpstream: Boolean = false,
    )

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val serializer = kotlinx.serialization.builtins.ListSerializer(Stored.serializer())

    fun encode(edits: List<DiaryEdit>): String =
        if (edits.isEmpty()) "" else json.encodeToString(
            serializer,
            edits.map { Stored(it.field, it.value, it.original, it.changedUpstream) },
        )

    fun decode(raw: String): List<DiaryEdit> {
        if (raw.isBlank()) return emptyList()
        val stored = runCatching { json.decodeFromString(serializer, raw) }.getOrNull() ?: return emptyList()
        return stored.filter { it.field.isNotBlank() }
            .map { DiaryEdit(it.field, it.value, it.original, it.changedUpstream) }
    }
}

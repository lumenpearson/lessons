package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.ManageApi
import com.lumenpearson.lessons.core.data.network.dto.BellPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.BellPeriodsDto
import com.lumenpearson.lessons.core.data.network.dto.BellScheduleInDto
import com.lumenpearson.lessons.core.data.network.dto.BellSchedulePatchDto
import com.lumenpearson.lessons.core.data.network.dto.ClassDeleteDto
import com.lumenpearson.lessons.core.data.network.dto.ClassPatchDto
import com.lumenpearson.lessons.core.data.network.dto.RequestDecisionInDto
import com.lumenpearson.lessons.core.data.network.dto.SubjectInDto
import com.lumenpearson.lessons.core.data.network.dto.SubjectPatchDto
import com.lumenpearson.lessons.core.data.network.dto.TimetableImportInDto
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Twenty-two calls over [ManageApi], and one rule about what a failure means.
 *
 * There is no state in this class on purpose. Nothing here is cached, nothing
 * is remembered between calls, and no answer is written to the database: this
 * is somebody editing the class, and the next screen that reads the timetable
 * reads it from the server like every other screen does. A repository that kept
 * a copy of the subject list would be a second place for it to be wrong.
 *
 * The one thing it does do is classify: every call goes through [call], which
 * turns whatever was thrown into a [ManageFailure]. That is what lets a screen
 * ask "is the page still mine" instead of parsing an English sentence out of an
 * error body.
 */
internal class ManageRepositoryImpl(
    private val api: ManageApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Called when the server refuses this device's token.
     *
     * Same bearer as the read API and therefore the same single answer: a
     * `401` here means the token was revoked or the class was deleted — by
     * this phone or from the bot — and the app must leave rather than keep a
     * page of buttons that will all fail. Deliberately *not* wired to the
     * `403`s: those say the role changed, and the device is still in the class.
     */
    private val onTokenRejected: suspend () -> Unit = {},
) : ManageRepository {

    override suspend fun classCard(): Result<ManagedClass> = call {
        api.classCard().toDomain()
    }

    override suspend fun updateClass(
        before: ManagedClass,
        edit: ClassEdit,
    ): Result<ManagedClass> = call {
        api.updateClass(
            ClassPatchDto(
                name = changedValue(before.name, edit.name),
                school = changedOrCleared(before.school, edit.school),
                city = changedOrCleared(before.city, edit.city),
                timezone = changedValue(before.timezone, edit.timezone),
            ),
        ).toDomain()
    }

    // `.map { }` rather than a trailing `Unit`: the answer is `DeletedOut`, and
    // the id in it names a row the caller already knows about. Throwing it away
    // in the type is clearer than throwing it away in a statement.
    override suspend fun deleteClass(confirmName: String): Result<Unit> =
        call { api.deleteClass(ClassDeleteDto(confirmName = confirmName.trim())) }.map { }

    override suspend fun searchSchools(query: String): Result<SchoolPage> = call {
        api.searchSchools(query = query.trim(), pageSize = SCHOOL_SEARCH_LIMIT).toDomain()
    }

    override suspend fun subjects(): Result<List<ManagedSubject>> = call {
        api.subjects().map { it.toDomain() }
    }

    override suspend fun createSubject(form: SubjectForm): Result<SubjectSaved> = call {
        api.createSubject(
            SubjectInDto(
                name = form.name.trim(),
                shortName = form.shortName?.trim()?.ifBlank { null },
                teacher = form.teacher?.trim()?.ifBlank { null },
                color = form.color?.trim()?.ifBlank { null },
            ),
        ).toDomain()
    }

    override suspend fun updateSubject(
        before: ManagedSubject,
        form: SubjectForm,
    ): Result<SubjectSaved> = call {
        api.updateSubject(
            before.id,
            SubjectPatchDto(
                name = changedValue(before.name, form.name),
                shortName = changedOrCleared(before.shortName, form.shortName),
                teacher = changedOrCleared(before.teacher, form.teacher),
                color = changedOrCleared(before.color, form.color),
            ),
        ).toDomain()
    }

    override suspend fun deleteSubject(id: Long): Result<Unit> =
        call { api.deleteSubject(id) }.map { }

    override suspend fun bells(): Result<List<BellSchedule>> = call {
        api.bells().map { it.toDomain() }
    }

    override suspend fun createBellSchedule(
        name: String,
        periods: List<BellPeriod>,
    ): Result<BellSchedule> = call {
        api.createBellSchedule(
            BellScheduleInDto(name = name.trim(), periods = periods.map { it.toDto() }),
        ).toDomain()
    }

    override suspend fun renameBellSchedule(id: Long, name: String): Result<BellSchedule> = call {
        api.updateBellSchedule(id, BellSchedulePatchDto(name = name.trim())).toDomain()
    }

    override suspend fun makeBellScheduleDefault(id: Long): Result<BellSchedule> = call {
        // `true` only, ever: the server answers `false` with a 422, and this
        // interface has no method that would want to send it.
        api.updateBellSchedule(id, BellSchedulePatchDto(isDefault = true)).toDomain()
    }

    override suspend fun writeBellPeriods(
        id: Long,
        periods: List<BellPeriod>,
    ): Result<BellSchedule> = call {
        api.writeBellPeriods(id, BellPeriodsDto(periods = periods.map { it.toDto() })).toDomain()
    }

    override suspend fun deleteBellSchedule(id: Long): Result<Unit> =
        call { api.deleteBellSchedule(id) }.map { }

    override suspend fun timetable(): Result<TimetableExport> = call {
        api.timetable().toDomain()
    }

    override suspend fun importTimetable(
        text: String,
        replace: Boolean,
    ): Result<TimetableImport> = call {
        api.importTimetable(TimetableImportInDto(text = text, replace = replace)).toDomain()
    }

    override suspend fun devices(includeRevoked: Boolean): Result<List<ManagedDevice>> = call {
        api.devices(includeRevoked).map { it.toDomain() }
    }

    override suspend fun revokeDevice(id: Long): Result<ManagedDevice> = call {
        api.revokeDevice(id).toDomain()
    }

    override suspend fun unlinkDevice(id: Long): Result<ManagedDevice> = call {
        api.unlinkDevice(id).toDomain()
    }

    override suspend fun log(limit: Int, offset: Int): Result<AuditPage> = call {
        api.log(
            // Clamped here rather than sent as typed: the server answers an
            // out-of-range limit with a 422, and a page turn is not a place to
            // learn that the app asked wrongly.
            limit = limit.coerceIn(1, ManageRepository.LOG_PAGE_MAX),
            offset = offset.coerceAtLeast(0),
        ).toDomain()
    }

    override suspend fun stats(): Result<ClassStats> = call {
        api.stats().toDomain()
    }

    override suspend fun requests(): Result<List<AccessRequest>> = call {
        api.requests().map { it.toDomain() }
    }

    override suspend fun approveRequest(id: Long, role: ClassRole?): Result<RequestDecision> =
        call {
            api.approveRequest(
                id,
                // An absent role means "the one that was asked for"; the server
                // reads a null field as absent, which is exactly right here.
                RequestDecisionInDto(role = role?.name?.lowercase()),
            ).toDomain()
        }

    override suspend fun declineRequest(id: Long): Result<RequestDecision> = call {
        api.declineRequest(id).toDomain()
    }

    private fun BellPeriod.toDto(): BellPeriodDto = BellPeriodDto(
        index = index,
        startsAt = startsAt.format(WireTime),
        endsAt = endsAt.format(WireTime),
    )

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(ioDispatcher) {
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val classified = ManageFailure.of(failure)
            if (classified is ManageFailure.SignedOut) onTokenRejected()
            Result.failure(classified)
        }
    }

    private companion object {
        /**
         * The directory's own ceiling, asked for explicitly so one search is
         * one request. See [ManageRepository.searchSchools].
         */
        const val SCHOOL_SEARCH_LIMIT: Int = 20

        /**
         * `HH:mm:ss`, which is what a `datetime.time` accepts and what the
         * server sends back. [LocalTime.toString] drops the seconds on a whole
         * minute, and a paste format that round-trips deserves one spelling.
         */
        val WireTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/**
 * A field that cannot be null, sent only if it actually changed.
 *
 * The server writes **one audit line per field it is given**, so sending all
 * four boxes of a form every time fills «📜 Журнал» with «class.name: 9А» for a
 * name nobody touched — four lines for a change of city, on the one page that
 * exists to say what changed. An absent field is «не трогать», which is exactly
 * what an untouched box means.
 *
 * @return the new value, or `null` to leave the field out of the patch.
 */
internal fun changedValue(before: String, after: String): String? =
    after.trim().takeIf { it != before.trim() }

/**
 * A field that *can* be null, in its three states.
 *
 * `null` leaves the field out of the patch; [JsonNull] is the explicit null
 * that clears it, which a blank box means and an omitted field never could;
 * anything else is the new value. See the file comment in `ManageDto` for why
 * this is a [JsonElement] rather than a Kotlin `String?`.
 */
internal fun changedOrCleared(before: String?, after: String?): JsonElement? {
    val cleaned = after?.trim()?.ifBlank { null }
    if (cleaned == before?.trim()?.ifBlank { null }) return null
    return cleaned?.let(::JsonPrimitive) ?: JsonNull
}

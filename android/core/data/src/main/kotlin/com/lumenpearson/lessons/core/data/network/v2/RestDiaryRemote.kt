package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.dto.WireFormats
import com.lumenpearson.lessons.core.data.network.v2.dto.BatchUpdateCorrectionsRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ClearCorrectionsRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CorrectionKeyDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CorrectionUpdateDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ResetCorrectionsRequestDto
import com.lumenpearson.lessons.core.data.repository.DiaryCapabilities
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryHandover
import java.time.LocalDate
import retrofit2.Retrofit

internal class RestDiaryRemote(retrofit: Retrofit) : DiaryRemote,
    DiarySessionRemote by RestDiarySessionRemote(retrofit.create(V2DiarySessionApi::class.java)),
    DiaryReadRemote by RestDiaryReadRemote(retrofit.create(V2DiaryReadApi::class.java)),
    DiaryCorrectionRemote by RestDiaryCorrectionRemote(retrofit.create(V2DiaryCorrectionApi::class.java))

private class RestDiarySessionRemote(private val api: V2DiarySessionApi) : DiarySessionRemote {
    override suspend fun capabilities(): DiaryCapabilities {
        val value = RestRefusals.body(RestRefusals.request { api.capabilities(it) }).capabilities
            ?: throw malformedV2Payload("The response has no diary capabilities")
        return DiaryCapabilities(value.enabled, value.providers.associate { it.provider to it.regions.toSet() })
    }

    override suspend fun registerSession(handover: DiaryHandover) =
        RestRefusals.request { api.register(handover.restRequest(), it) }.let { response ->
            val session = RestRefusals.body(response).session
                ?: throw malformedV2Payload("The response has no diary session", response.code())
            session.toRegistration(handover.target, response.code())
        }

    override suspend fun logout() {
        RestRefusals.body(RestRefusals.request { api.logout(it) })
    }
}

private class RestDiaryReadRemote(private val api: V2DiaryReadApi) : DiaryReadRemote {
    override suspend fun students(observation: CallObservation) =
        RestRefusals.body(RestRefusals.request(observation) { api.students(it) }).students.map { it.toDomain() }

    override suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation,
    ) =
        RestRefusals.body(RestRefusals.request(observation) {
            api.schedule(studentId, from.toString(), to.toString(), it)
        })
            .scheduleDays.flatMap { day ->
                val date = WireFormats.parseDate(day.date)
                if (date == null) emptyList() else day.lessons.map { it.toDomain(date) }
            }

    override suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation,
    ) =
        RestRefusals.body(RestRefusals.request(observation) {
            api.homework(studentId, from.toString(), to.toString(), it)
        })
            .homework.mapNotNull { it.toDomain() }

    override suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation,
    ) =
        RestRefusals.body(RestRefusals.request(observation) {
            api.grades(studentId, from.toString(), to.toString(), it)
        })
            .marks.mapNotNull { it.toDomain() }

    override suspend fun periods(studentId: Long, observation: CallObservation) =
        RestRefusals.body(RestRefusals.request(observation) { api.periods(studentId, it) })
            .periods.map { it.toDomain() }
}

private class RestDiaryCorrectionRemote(private val api: V2DiaryCorrectionApi) : DiaryCorrectionRemote {
    override suspend fun overrides(studentId: Long, observation: CallObservation) =
        RestRefusals.body(RestRefusals.request(observation) { api.overrides(studentId, it) })
            .corrections.mapNotNull { it.toDomain() }

    override suspend fun correct(
        studentId: Long,
        target: String,
        field: DiaryField,
        value: String,
        original: String?,
        observation: CallObservation,
    ) {
        val body = BatchUpdateCorrectionsRequestDto(
            corrections = listOf(CorrectionUpdateDto(target, field.wire(), value, original)),
        )
        RestRefusals.body(RestRefusals.request(observation) { api.correct(studentId, body, it) })
    }

    override suspend fun reset(
        studentId: Long,
        target: String,
        field: DiaryField,
        observation: CallObservation,
    ) {
        val body = ResetCorrectionsRequestDto(corrections = listOf(CorrectionKeyDto(target, field.wire())))
        RestRefusals.body(RestRefusals.request(observation) { api.reset(studentId, body, it) })
    }

    override suspend fun resetAll(studentId: Long, observation: CallObservation) {
        RestRefusals.body(RestRefusals.request(observation) { api.clear(studentId, ClearCorrectionsRequestDto(), it) })
    }
}

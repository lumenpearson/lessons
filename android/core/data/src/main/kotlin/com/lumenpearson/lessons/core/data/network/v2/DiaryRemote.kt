package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.DiaryCapabilities
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryHandover
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryOverrideRecord
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRegistration
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.LocalDate

/** One selected transport for the complete consumed diary surface. */
internal interface DiaryRemote : DiarySessionRemote, DiaryReadRemote, DiaryCorrectionRemote

internal interface DiarySessionRemote {
    suspend fun capabilities(): DiaryCapabilities
    suspend fun registerSession(handover: DiaryHandover): DiaryRegistration
    suspend fun logout()
}

internal interface DiaryReadRemote {
    suspend fun students(observation: CallObservation = CallObservation()): List<DiaryStudent>
    suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation = CallObservation(),
    ): List<DiaryLesson>
    suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation = CallObservation(),
    ): List<DiaryHomework>
    suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation = CallObservation(),
    ): List<DiaryMark>
    suspend fun periods(studentId: Long, observation: CallObservation = CallObservation()): List<DiaryPeriod>
}

internal interface DiaryCorrectionRemote {
    suspend fun overrides(studentId: Long, observation: CallObservation = CallObservation()): List<DiaryOverrideRecord>
    suspend fun correct(
        studentId: Long,
        target: String,
        field: DiaryField,
        value: String,
        original: String?,
        observation: CallObservation = CallObservation(),
    )
    suspend fun reset(
        studentId: Long,
        target: String,
        field: DiaryField,
        observation: CallObservation = CallObservation(),
    )
    suspend fun resetAll(studentId: Long, observation: CallObservation = CallObservation())
}

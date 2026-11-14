package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.ListStudentsRequest
import com.lumenpearson.lessons.contract.v2.ListStudentsResponse
import com.lumenpearson.lessons.contract.v2.ListScheduleDaysRequest
import com.lumenpearson.lessons.contract.v2.ListScheduleDaysResponse
import com.lumenpearson.lessons.contract.v2.ListDiaryHomeworkRequest
import com.lumenpearson.lessons.contract.v2.ListDiaryHomeworkResponse
import com.lumenpearson.lessons.contract.v2.ListMarksRequest
import com.lumenpearson.lessons.contract.v2.ListMarksResponse
import com.lumenpearson.lessons.contract.v2.ListPeriodsRequest
import com.lumenpearson.lessons.contract.v2.ListPeriodsResponse
import com.lumenpearson.lessons.core.data.network.dto.WireFormats
import java.time.LocalDate

internal class RpcDiaryReadRemote(private val rpc: RpcCalls) : DiaryReadRemote {
    override suspend fun students(observation: CallObservation) =
        rpc.unary(Students, ListStudentsRequest.getDefaultInstance(), observation)
            .studentsList.map { it.canonicalStudent().toDomain() }

    override suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation,
    ) =
        rpc.unary(Schedule, ListScheduleDaysRequest.newBuilder().setStudentId(studentId)
            .setStartDate(from.toString()).setEndDate(to.toString()).build(), observation)
            .scheduleDaysList.flatMap { day ->
                val date = WireFormats.parseDate(day.date)
                if (date == null) emptyList() else day.lessonsList.map { it.canonicalLesson().toDomain(date) }
            }

    override suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation,
    ) =
        rpc.unary(Homework, ListDiaryHomeworkRequest.newBuilder().setStudentId(studentId)
            .setStartDate(from.toString()).setEndDate(to.toString()).build(), observation)
            .homeworkList.mapNotNull { it.canonicalHomework().toDomain() }

    override suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
        observation: CallObservation,
    ) =
        rpc.unary(Grades, ListMarksRequest.newBuilder().setStudentId(studentId)
            .setStartDate(from.toString()).setEndDate(to.toString()).build(), observation)
            .marksList.mapNotNull { it.canonicalMark().toDomain() }

    override suspend fun periods(studentId: Long, observation: CallObservation) =
        rpc.unary(Periods, ListPeriodsRequest.newBuilder().setStudentId(studentId).build(), observation)
            .periodsList.map { it.canonicalPeriod().toDomain() }

    private companion object {
        val Students = MethodSpec("lessons.v2.DiaryService/ListStudents",
            ListStudentsRequest::class, ListStudentsResponse::class, StreamType.UNARY)
        val Schedule = MethodSpec("lessons.v2.DiaryService/ListScheduleDays",
            ListScheduleDaysRequest::class, ListScheduleDaysResponse::class, StreamType.UNARY)
        val Homework = MethodSpec("lessons.v2.DiaryService/ListDiaryHomework",
            ListDiaryHomeworkRequest::class, ListDiaryHomeworkResponse::class, StreamType.UNARY)
        val Grades = MethodSpec("lessons.v2.DiaryService/ListMarks",
            ListMarksRequest::class, ListMarksResponse::class, StreamType.UNARY)
        val Periods = MethodSpec("lessons.v2.DiaryService/ListPeriods",
            ListPeriodsRequest::class, ListPeriodsResponse::class, StreamType.UNARY)
    }
}

package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.DiaryCorrection
import com.lumenpearson.lessons.contract.v2.DiaryEdit
import com.lumenpearson.lessons.contract.v2.DiaryHomework
import com.lumenpearson.lessons.contract.v2.DiaryLesson
import com.lumenpearson.lessons.contract.v2.DiaryMark
import com.lumenpearson.lessons.contract.v2.DiaryPeriod
import com.lumenpearson.lessons.contract.v2.DiaryScheduleDay
import com.lumenpearson.lessons.contract.v2.DiaryStudent
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryCorrectionDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryEditDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryMarkDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryScheduleDayDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryStudentDto
import com.lumenpearson.lessons.core.data.network.v2.dto.MarkKindWireSerializer

/** Both bindings share normalization without serializing binary replies through JSON. */
internal fun DiaryStudent.canonicalStudent() = DiaryStudentDto(
    id, firstName, lastName, middleName.takeIf { hasMiddleName() }, fullName,
    school.takeIf { hasSchool() }, className.takeIf { hasClassName() },
)

private fun DiaryEdit.canonicalEdit() = DiaryEditDto(
    field, value, original.takeIf { hasOriginal() }, changedUpstream,
)

internal fun DiaryLesson.canonicalLesson() = DiaryLessonDto(
    number.takeIf { hasNumber() }, subject, startsAt.takeIf { hasStartsAt() }, endsAt.takeIf { hasEndsAt() },
    room.takeIf { hasRoom() }, teacher.takeIf { hasTeacher() }, homework.takeIf { hasHomework() },
    topic.takeIf { hasTopic() }, target, editsList.map { it.canonicalEdit() }, ambiguous,
)

internal fun DiaryScheduleDay.canonicalDiaryDay() = DiaryScheduleDayDto(
    date, lessonsList.map { it.canonicalLesson() },
)

internal fun DiaryHomework.canonicalHomework() = DiaryHomeworkDto(
    id.takeIf { hasId() }, dueDate, subject, text, teacher.takeIf { hasTeacher() },
    target, editsList.map { it.canonicalEdit() }, ambiguous,
)

internal fun DiaryMark.canonicalMark() = DiaryMarkDto(
    id.takeIf { hasId() }, subjectId.takeIf { hasSubjectId() }, subject, date.takeIf { hasDate() }, value,
    MarkKindWireSerializer.wireName(kindValue), reason.takeIf { hasReason() }, comment.takeIf { hasComment() },
)

internal fun DiaryPeriod.canonicalPeriod() = DiaryPeriodDto(
    id, name, startsOn.takeIf { hasStartsOn() }, endsOn.takeIf { hasEndsOn() }, isCurrent,
)

internal fun DiaryCorrection.canonicalCorrection() = DiaryCorrectionDto(
    target, field, value, originalWhenWritten.takeIf { hasOriginalWhenWritten() },
)

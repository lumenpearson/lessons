package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.dto.DiaryEditDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryMarkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryStudentDto
import com.lumenpearson.lessons.core.data.network.dto.WireFormats

/**
 * Wire shapes to domain types.
 *
 * The one rule here is the one the bundle mappers already follow: a row whose
 * date cannot be read is dropped, never defaulted and never fatal. A lesson
 * without a date has nowhere to be drawn, so it goes; a *time* that cannot be
 * read only costs the time, because the diary publishes plenty of lessons
 * without one and hiding them would misreport the day.
 *
 * Blank strings become `null` on the way through: the server sends `null` for
 * "no room", but the upstream it reads has been known to send `""`, and two
 * spellings of nothing would be two renderings of it.
 */

internal fun DiaryStudentDto.toDomain(): DiaryStudent {
    val first = firstName.trim()
    val last = lastName.trim()
    val middle = middleName.cleaned()
    return DiaryStudent(
        id = id,
        firstName = first,
        lastName = last,
        middleName = middle,
        // Falls back to the parts when the server sends no full name, so a
        // picker never has to render an empty row.
        fullName = fullName.trim().ifBlank {
            listOfNotNull(last.ifBlank { null }, first.ifBlank { null }, middle)
                .joinToString(" ")
        },
        school = school.cleaned(),
        className = className.cleaned(),
    )
}

/**
 * `null` when there is no field name, which is the one thing an edit cannot do
 * without: it is what the «сбросить» button next to the value resets.
 *
 * Dropping one edit costs the marker over one field, never the lesson — the
 * value itself already arrived corrected, because the server lays corrections
 * over on the way out rather than leaving the client to apply them.
 *
 * [DiaryEdit.value] is kept exactly as it came back, untrimmed: it is what
 * somebody typed, and trimming it here would make the app show something other
 * than what «сбросить» would remove.
 */
internal fun DiaryEditDto.toDomain(): DiaryEdit? {
    val name = field.trim().ifBlank { return null }
    return DiaryEdit(
        field = name,
        value = value,
        original = original.cleaned(),
        changedUpstream = changedUpstream,
    )
}

/** `null` when the date is unreadable — the only field a lesson cannot do without. */
internal fun DiaryLessonDto.toDomain(): DiaryLesson? {
    val day = WireFormats.parseDate(date) ?: return null
    return DiaryLesson(
        date = day,
        number = number?.takeIf { it > 0 },
        subject = subject.trim(),
        startsAt = WireFormats.parseTime(startsAt),
        endsAt = WireFormats.parseTime(endsAt),
        room = room.cleaned(),
        teacher = teacher.cleaned(),
        homework = homework.cleaned(),
        topic = topic.cleaned(),
        // Not trimmed and not defaulted: the key is composed from a subject
        // name, so its whitespace is part of it, and a key we tidied is a key
        // the server would file a correction under some other lesson.
        target = target,
        edits = edits.mapNotNull { it.toDomain() },
        ambiguous = ambiguous,
    )
}

/** `null` when the due date or the text is missing; an assignment needs both. */
internal fun DiaryHomeworkDto.toDomain(): DiaryHomework? {
    val due = WireFormats.parseDate(dueDate) ?: return null
    val body = text.trim().ifBlank { return null }
    return DiaryHomework(
        id = id,
        dueDate = due,
        subject = subject.trim(),
        text = body,
        teacher = teacher.cleaned(),
        target = target,
        edits = edits.mapNotNull { it.toDomain() },
    )
}

/**
 * `null` for a row this build cannot act on.
 *
 * A correction whose field is unknown is dropped rather than shown: this list
 * is the screen that resets things, and a row it cannot name a field for would
 * be a row whose reset button has no field to send. It stays on the server —
 * «сбросить всё» still reaches it — which is the honest outcome for a row a
 * newer server knows about and this build does not.
 */
internal fun DiaryOverrideDto.toDomain(): DiaryOverrideRecord? {
    val key = target.ifBlank { return null }
    val named = DiaryField.fromWire(field) ?: return null
    return DiaryOverrideRecord(
        target = key,
        field = named,
        value = value,
        original = original.cleaned(),
    )
}

/**
 * `null` for a cell with nothing in it.
 *
 * The date is *not* required: the diary carries term marks and year marks with
 * no date at all, and those are the ones a parent looks for first.
 */
internal fun DiaryMarkDto.toDomain(): DiaryMark? {
    val cell = value.trim().ifBlank { return null }
    return DiaryMark(
        id = id,
        subjectId = subjectId,
        subject = subject.trim(),
        date = WireFormats.parseDate(date),
        value = cell,
        kind = DiaryMarkKind.fromWire(kind),
        reason = reason.cleaned(),
        comment = comment.cleaned(),
    )
}

internal fun DiaryPeriodDto.toDomain(): DiaryPeriod = DiaryPeriod(
    id = id,
    name = name.trim(),
    startsOn = WireFormats.parseDate(startsOn),
    endsOn = WireFormats.parseDate(endsOn),
    isCurrent = isCurrent,
)

/** Trimmed, with "" and "   " treated as the `null` the server meant. */
private fun String?.cleaned(): String? = this?.trim()?.ifBlank { null }

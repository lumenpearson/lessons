package com.lumenpearson.lessons.ui.today

import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.model.Lesson
import java.time.LocalDate

/**
 * The diary's marks for [date], pinned to the lessons of the class timetable.
 *
 * Two sources meet here and neither knows the other: the timetable is the
 * class's, typed into the bot, and the marks are the diary's, read with the
 * family's own sign-in. What they share is a date and a subject's name, and
 * the name is typed differently often enough — «Физическая Культура» in one,
 * «Физическая культура» in the other — that it is compared folded: case,
 * «ё», and runs of spaces do not count.
 *
 * A mark carries no lesson number, so a subject taught twice that day cannot
 * say which of the two a mark was for. It goes on the first of them: that is
 * where a pupil looking down the day meets the subject, and putting it on both
 * would read as two marks.
 *
 * Only [DiaryMarkKind.GRADE] is drawn. An absence or a remark is not a mark,
 * and the diary screen keeps them apart for the same reason — «Н» beside a
 * lesson reads as a grade of some kind.
 */
fun gradesByLesson(
    lessons: List<Lesson>,
    marks: List<DiaryMark>,
    date: LocalDate,
): Map<Lesson, List<String>> {
    if (lessons.isEmpty() || marks.isEmpty()) return emptyMap()
    val firstBySubject = lessons
        .sortedBy { it.startsAt }
        .filterNot { it.isCancelled }
        .groupBy { it.subject.foldedSubject() }
        .mapValues { (_, same) -> same.first() }

    val result = linkedMapOf<Lesson, MutableList<String>>()
    marks
        .filter { it.date == date && it.kind == DiaryMarkKind.GRADE && it.value.isNotBlank() }
        .forEach { mark ->
            val lesson = firstBySubject[mark.subject.foldedSubject()] ?: return@forEach
            result.getOrPut(lesson) { mutableListOf() } += mark.value.trim()
        }
    return result
}

/** A subject's name as the two sources can agree on it; see [gradesByLesson]. */
internal fun String.foldedSubject(): String =
    lowercase().replace('ё', 'е').trim().replace(Regex("\\s+"), " ")

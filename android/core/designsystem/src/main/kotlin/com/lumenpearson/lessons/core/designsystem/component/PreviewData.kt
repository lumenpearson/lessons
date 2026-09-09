package com.lumenpearson.lessons.core.designsystem.component

import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolEvent
import java.time.LocalTime

/**
 * One realistic school day, shared by every `@Preview` in this module.
 *
 * Internal and centralised so the previews all show the same 8 «Б» timetable:
 * when a component changes, the diff in the preview pane is the change and not
 * a different set of invented subjects.
 */
internal object PreviewData {

    val russian = Lesson(
        index = 1,
        subject = "Русский язык",
        startsAt = LocalTime.of(8, 30),
        endsAt = LocalTime.of(9, 15),
        room = "212",
        teacher = "Соколова А. В.",
        colorHex = "#4453BE",
    )

    val algebra = Lesson(
        index = 2,
        subject = "Алгебра",
        startsAt = LocalTime.of(9, 25),
        endsAt = LocalTime.of(10, 10),
        room = "305",
        teacher = "Иванова Н. П.",
        colorHex = "#1D6B57",
    )

    val physics = Lesson(
        index = 3,
        subject = "Физика",
        startsAt = LocalTime.of(10, 25),
        endsAt = LocalTime.of(11, 10),
        room = "118",
        teacher = "Гордеев М. С.",
        isReplaced = true,
        note = "Вместо химии",
    )

    val history = Lesson(
        index = 4,
        subject = "История",
        startsAt = LocalTime.of(11, 25),
        endsAt = LocalTime.of(12, 10),
        room = "402",
        teacher = "Лебедева О. И.",
        isCancelled = true,
    )

    val literature = Lesson(
        index = 5,
        subject = "Литература",
        startsAt = LocalTime.of(12, 30),
        endsAt = LocalTime.of(13, 15),
        room = "212",
        teacher = "Соколова А. В.",
    )

    val day: List<Lesson> = listOf(russian, algebra, physics, history, literature)

    val canteen = SchoolEvent(
        title = "Обед для 8-х классов",
        kind = EventKind.CANTEEN,
        startsAt = LocalTime.of(11, 10),
        endsAt = LocalTime.of(11, 25),
        location = "Столовая",
    )

    val homework = HomeworkItem(
        subject = "Алгебра",
        text = "§ 14, номера 412–418. Задачу 418 разобрать письменно с пояснением каждого шага.",
        attachmentUrl = "https://example.org/hw/algebra-14.pdf",
    )

    val homeworkWithoutAttachment = HomeworkItem(
        subject = "Литература",
        text = "Прочитать главы 3–5 «Капитанской дочки», выписать характеристику Гринёва.",
    )
}

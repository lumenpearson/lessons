package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.HomeworkRow
import com.lumenpearson.lessons.core.designsystem.component.LessonGroup
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.ui.common.asDayMonth
import com.lumenpearson.lessons.ui.common.asFullWeekday
import com.lumenpearson.lessons.ui.common.timeRange
import java.time.Duration
import java.time.LocalDate

/**
 * Which lesson a sheet is about, in a form a bundle takes.
 *
 * A date and a lesson number, never the [Lesson] itself: [Lesson] lives in
 * `:core:model`, a pure JVM module with no Android on its classpath, so it is
 * neither `Parcelable` nor anything else saved instance state takes — and
 * making it so would mean putting Android into the one module that is
 * deliberately without it.
 *
 * The pair names one lesson: the server resolves a date into at most one lesson
 * per number (`schedule.py` builds the day as slots keyed by it, so a template
 * cell and a substitution at the same number are the same slot), and everything
 * between there and this screen only drops rows and sorts them.
 */
internal data class OpenLesson(val date: LocalDate, val index: Int)

/**
 * Which of the calendar's two sheets is up, and what about.
 *
 * One saveable holder rather than two `rememberSaveable`s in the screen, for the
 * reason `SupportSheetState` gives. Saveable at all because both were plain
 * `remember`s: turning the phone closed the lesson sheet, which is the only
 * place the room, the teacher and the day's homework for that subject can be
 * read, and put the reader back on the grid they had opened it from.
 */
@Stable
internal class ScheduleSheetState(lesson: OpenLesson?, day: LocalDate?) {

    /** The lesson [ScheduleSheets] is showing, as the key it is looked up by. */
    var lesson: OpenLesson? by mutableStateOf(lesson)

    /** The date whose [DaySheet] is up. */
    var day: LocalDate? by mutableStateOf(day)

    /**
     * Opens [lesson]'s sheet, closing the day sheet it may have been tapped in:
     * two stacked scrims is a dead end a back gesture has to be used twice to
     * escape — see [DaySheet].
     */
    fun show(date: LocalDate, lesson: Lesson) {
        day = null
        this.lesson = OpenLesson(date, lesson.index)
    }

    companion object {
        internal val Saver = listSaver<ScheduleSheetState, String>(
            save = {
                listOf(
                    it.lesson?.date?.toString().orEmpty(),
                    it.lesson?.index?.toString().orEmpty(),
                    it.day?.toString().orEmpty(),
                )
            },
            restore = { saved ->
                val date = savedDate(saved[0])
                val index = saved[1].toIntOrNull()
                ScheduleSheetState(
                    lesson = if (date == null || index == null) null else OpenLesson(date, index),
                    day = savedDate(saved[2]),
                )
            },
        )
    }
}

/**
 * [text] as a date, or `null` when it is not one.
 *
 * A saved bundle is this process's own, so a malformed one is not an attack —
 * but a build that wrote another format is one update away, and the alternative
 * to refusing it is `LocalDate.parse` throwing under a reader who did nothing
 * but turn their phone. `decodeBellRows` refuses for the same reason.
 */
private fun savedDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

@Composable
internal fun rememberScheduleSheets(): ScheduleSheetState =
    rememberSaveable(saver = ScheduleSheetState.Saver) {
        ScheduleSheetState(lesson = null, day = null)
    }

/**
 * The calendar's two sheets, hosted in one place.
 *
 * The lesson is looked up in [days] on every composition rather than held: what
 * survives a rotation is which lesson the sheet was about, and by the time the
 * state is restored the week can have been reloaded, the substitution withdrawn
 * or the reader left on another week entirely. A lesson that is no longer there
 * opens no sheet, which is the honest outcome — the alternative is a room, a
 * teacher and a homework list belonging to a lesson that has stopped existing.
 */
@Composable
internal fun ScheduleSheets(
    sheets: ScheduleSheetState,
    days: List<WeekDayUi>,
    showTeacher: Boolean,
    showEvents: Boolean,
    showHomework: Boolean,
) {
    sheets.lesson?.let { open ->
        val day = days.firstOrNull { it.date == open.date }?.day
        val lesson = day?.activeLessons?.firstOrNull { it.index == open.index }
        if (day != null && lesson != null) {
            LessonSheet(
                lesson = lesson,
                date = open.date,
                homework = day.homework
                    .filter { it.subject.equals(lesson.subject, ignoreCase = true) },
                showTeacher = showTeacher,
                onDismiss = { sheets.lesson = null },
            )
        }
    }

    sheets.day?.let { date ->
        DaySheet(
            day = days.firstOrNull { it.date == date },
            date = date,
            showTeacher = showTeacher,
            showEvents = showEvents,
            showHomework = showHomework,
            onLessonClick = { tapped -> sheets.show(date, tapped) },
            onDismiss = { sheets.day = null },
        )
    }
}

/**
 * Everything about one lesson, in a sheet.
 *
 * The timetable row has space for a subject, a time and one trailing detail, and
 * the rest of what the server sends — the teacher, a replacement note, the
 * homework set for that subject that day — had nowhere to be read. A sheet is
 * where it goes: it arrives from the bottom edge like every other surface in the
 * app, and it costs the row nothing.
 */
@Composable
fun LessonSheet(
    lesson: Lesson,
    date: LocalDate,
    homework: List<HomeworkItem>,
    showTeacher: Boolean,
    onDismiss: () -> Unit,
) {
    val minutes = Duration.between(lesson.startsAt, lesson.endsAt).toMinutes().toInt()

    LessonsBottomSheet(onDismissRequest = onDismiss, title = lesson.subject) {
        Row(
            modifier = Modifier.padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip(text = correctedString(R.string.schedule_lesson_index, lesson.index))
            if (lesson.isReplaced) {
                PillChip(text = correctedString(R.string.schedule_lesson_replaced))
            }
            if (lesson.isCancelled) {
                PillChip(
                    text = correctedString(R.string.schedule_lesson_cancelled),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            GroupItem(
                title = timeRange(lesson.startsAt, lesson.endsAt),
                subtitle = correctedString(R.string.schedule_duration, minutes),
                icon = Icons.Rounded.Schedule,
                tone = subjectTone(lesson.subject, lesson.colorHex),
            )
            lesson.room?.takeIf { it.isNotBlank() }?.let { room ->
                GroupItem(
                    title = room,
                    subtitle = correctedString(R.string.schedule_room),
                    icon = Icons.Rounded.MeetingRoom,
                    tone = accentTone(1),
                )
            }
            if (showTeacher) {
                lesson.teacher?.takeIf { it.isNotBlank() }?.let { teacher ->
                    GroupItem(
                        title = teacher,
                        subtitle = correctedString(R.string.schedule_teacher),
                        icon = Icons.Rounded.Person,
                        tone = accentTone(3),
                    )
                }
            }
            lesson.note?.takeIf { it.isNotBlank() }?.let { note ->
                GroupItem(
                    title = note,
                    subtitle = correctedString(R.string.schedule_note),
                    icon = Icons.Rounded.StickyNote2,
                    tone = if (lesson.isCancelled) errorTone() else accentTone(5),
                )
            }
        }

        SectionHeader(
            title = correctedString(R.string.schedule_homework),
            subtitle = date.asDayMonth(),
            modifier = Modifier.padding(horizontal = ScreenPadding - 16.dp),
        )
        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            if (homework.isEmpty()) {
                GroupItem(
                    title = correctedString(R.string.schedule_homework_empty),
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    tone = neutralTone(),
                )
            } else {
                homework.forEach { item -> HomeworkRow(item = item) }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Everything about one day, in a sheet.
 *
 * Reached from the month grid, where a cell has room for a number and three
 * dots, and from the "Подробно" action under the week. Tapping a lesson inside
 * hands over to [LessonSheet] rather than nesting a second sheet on top of this
 * one — two stacked scrims is a dead end a back gesture has to be used twice to
 * escape.
 *
 * The sheet honours the same two switches as the panel it was opened from: a
 * block hidden under the week reappearing inside this sheet would read as the
 * setting having failed rather than as a deliberate second chance.
 */
@Composable
fun DaySheet(
    day: WeekDayUi?,
    date: LocalDate,
    showTeacher: Boolean,
    showEvents: Boolean,
    showHomework: Boolean,
    onLessonClick: (Lesson) -> Unit,
    onDismiss: () -> Unit,
) {
    val schoolDay = day?.day
    val lessons = schoolDay?.activeLessons.orEmpty()

    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = "${date.asFullWeekday().replaceFirstChar { it.uppercase() }}, ${date.asDayMonth()}",
    ) {
        val kind = schoolDay?.kind?.takeIf { it != DayKind.NORMAL }
        if (day?.isToday == true || kind != null || schoolDay?.note != null) {
            Row(
                modifier = Modifier.padding(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (day?.isToday == true) {
                    PillChip(
                        text = correctedString(R.string.day_today),
                        selected = true,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                if (kind != null) PillChip(text = kind.asLabel())
                schoolDay?.note?.let { PillChip(text = it) }
            }
        }

        if (schoolDay == null) {
            Text(
                text = correctedString(R.string.week_no_data_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            Spacer(Modifier.height(8.dp))
            return@LessonsBottomSheet
        }

        if (lessons.isEmpty()) {
            Text(
                text = correctedString(R.string.week_day_off_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        } else {
            SectionHeader(
                title = pluralStringResource(R.plurals.lessons_count, lessons.size, lessons.size),
                modifier = Modifier.padding(horizontal = ScreenPadding - 16.dp),
            )
            LessonGroup(
                lessons = lessons,
                showTeacher = showTeacher,
                onLessonClick = onLessonClick,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        DayExtras(
            day = schoolDay,
            showEvents = showEvents,
            showHomework = showHomework,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A day's events and homework, under whatever showed its lessons.
 *
 * Both are optional and both were invisible before: the calendar drew lessons
 * and nothing else, so a class trip that replaces a lesson, and the homework set
 * for a day you are looking at, existed in the cache and nowhere on screen.
 *
 * @param showEvents a user setting; a school that never holds an event has
 *   nothing to gain from the switch, which is why it is not the same one as
 *   below.
 * @param showHomework a user setting. Separate from [showEvents] because for
 *   anybody who reads homework in its own tab this block is a duplicate, and
 *   that is a different complaint from "we have no events".
 */
@Composable
fun DayExtras(
    day: SchoolDay?,
    showEvents: Boolean,
    showHomework: Boolean,
    modifier: Modifier = Modifier,
) {
    if (day == null) return
    val events = if (showEvents) day.events else emptyList()
    val homework = if (showHomework) day.homework else emptyList()
    if (events.isEmpty() && homework.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (events.isNotEmpty()) {
            SectionHeader(title = correctedString(R.string.schedule_events))
            RoundedCardContainer {
                events.forEach { event ->
                    GroupItem(
                        title = event.title,
                        subtitle = listOfNotNull(
                            timeRange(event.startsAt, event.endsAt),
                            event.location?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        icon = Icons.Rounded.EventNote,
                        tone = accentTone(event.kind.ordinal),
                    )
                }
            }
        }

        if (homework.isNotEmpty()) {
            SectionHeader(title = correctedString(R.string.schedule_homework))
            RoundedCardContainer {
                homework.forEach { item -> HomeworkRow(item = item) }
            }
        }
    }
}

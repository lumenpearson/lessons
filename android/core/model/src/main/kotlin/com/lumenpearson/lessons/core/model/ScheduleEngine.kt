package com.lumenpearson.lessons.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Turns a cached [Timetable] plus a wall-clock instant into a [DayState].
 *
 * Deliberately pure and synchronous: the widget calls this on every tick, and a
 * unit test can walk a whole school day in a loop without touching Android.
 *
 * Precedence when several things overlap:
 *  1. an event that covers the moment, unless a lesson is running and the event
 *     did not claim to replace it;
 *  2. the lesson containing the moment;
 *  3. the gap before / between / after lessons.
 */
object ScheduleEngine {

    fun stateAt(timetable: Timetable, now: LocalDateTime): DayState {
        val today = now.toLocalDate()
        val day = timetable.day(today) ?: return DayState.NoData(today)
        return stateAt(day, now, nextHomeworkDay = { timetable.schoolDayAfter(today) })
    }

    fun stateAt(
        day: SchoolDay,
        now: LocalDateTime,
        nextHomeworkDay: () -> SchoolDay?,
    ): DayState {
        val time = now.toLocalTime()
        val lessons = day.activeLessons
        val current = lessons.firstOrNull { it.contains(time) }

        // An event wins over an empty gap always, and over a running lesson only
        // when it was marked as covering lessons (актовый зал вместо урока).
        val event = day.events
            .filter { it.contains(time) }
            .firstOrNull { current == null || it.coversLesson }
        if (event != null) {
            val next = lessons.firstOrNull { it.startsAt >= event.endsAt }
            return DayState.DuringEvent(
                event = event,
                next = next,
                endsIn = Duration.between(time, event.endsAt),
                progress = progress(time, event.startsAt, event.endsAt),
                validUntil = now.with(event.endsAt),
            )
        }

        if (current != null) {
            val next = lessons.firstOrNull { it.startsAt >= current.endsAt }
            return DayState.InLesson(
                current = current,
                next = next,
                endsIn = Duration.between(time, current.endsAt),
                progress = progress(time, current.startsAt, current.endsAt),
                validUntil = now.with(current.endsAt),
            )
        }

        if (lessons.isEmpty()) {
            return DayState.DayOff(
                date = day.date,
                kind = day.kind,
                note = day.note,
                homeworkDay = nextHomeworkDay(),
                validUntil = now.toLocalDate().plusDays(1).atStartOfDay(),
            )
        }

        val first = lessons.first()
        if (time < first.startsAt) {
            return DayState.BeforeSchool(
                next = first,
                startsIn = Duration.between(time, first.startsAt),
                validUntil = now.with(first.startsAt),
            )
        }

        val last = lessons.last()
        if (time >= last.endsAt) {
            return DayState.AfterSchool(
                finishedAt = last.endsAt,
                homeworkDay = nextHomeworkDay(),
                validUntil = now.toLocalDate().plusDays(1).atStartOfDay(),
            )
        }

        // Strictly between two lessons.
        val previous = lessons.last { it.endsAt <= time }
        val next = lessons.first { it.startsAt > time }
        return DayState.OnBreak(
            previous = previous,
            next = next,
            endsIn = Duration.between(time, next.startsAt),
            progress = progress(time, previous.endsAt, next.startsAt),
            validUntil = now.with(next.startsAt),
        )
    }

    /**
     * The next wall-clock moment the rendered state could change.
     *
     * The widget uses this to schedule one exact alarm instead of waking up
     * every minute, which is the difference between a widget that is pleasant
     * and one the battery menu complains about.
     */
    fun nextTransition(timetable: Timetable, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay()
        val day = timetable.day(today) ?: return midnight

        val boundaries = buildList {
            day.activeLessons.forEach { add(it.startsAt); add(it.endsAt) }
            day.events.forEach { add(it.startsAt); add(it.endsAt) }
        }

        val nowTime = now.toLocalTime()
        val nextToday = boundaries.filter { it > nowTime }.minOrNull()
        return nextToday?.let { today.atTime(it) } ?: midnight
    }

    /** Remaining lessons today, useful for the medium widget sizes. */
    fun remainingLessons(day: SchoolDay, time: LocalTime): List<Lesson> =
        day.activeLessons.filter { it.endsAt > time }

    private fun progress(now: LocalTime, start: LocalTime, end: LocalTime): Float {
        val total = Duration.between(start, end).toMillis()
        if (total <= 0L) return 1f
        val elapsed = Duration.between(start, now).toMillis()
        return (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    private fun Lesson.contains(time: LocalTime): Boolean = time >= startsAt && time < endsAt

    private fun SchoolEvent.contains(time: LocalTime): Boolean = time >= startsAt && time < endsAt
}

/** Convenience for callers that only have a date. */
fun Timetable.stateAt(now: LocalDateTime): DayState = ScheduleEngine.stateAt(this, now)

fun Timetable.todayOrNull(today: LocalDate): SchoolDay? = day(today)

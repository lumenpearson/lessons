package com.lumenpearson.lessons.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What is happening right now. This is the single value the widget renders and
 * the home screen headlines with.
 *
 * Every variant carries whatever the UI needs to draw itself without going back
 * to the timetable, so a widget size can pick a subset and stay dumb.
 */
sealed interface DayState {

    /** Wall-clock moment at which this state stops being true, if there is one. */
    val validUntil: LocalDateTime?

    /** Lessons have not started yet today. */
    data class BeforeSchool(
        val next: Lesson,
        val startsIn: Duration,
        override val validUntil: LocalDateTime?,
    ) : DayState

    /** A lesson is in progress. */
    data class InLesson(
        val current: Lesson,
        val next: Lesson?,
        val endsIn: Duration,
        val progress: Float,
        override val validUntil: LocalDateTime?,
    ) : DayState

    /** Between two lessons. */
    data class OnBreak(
        val previous: Lesson?,
        val next: Lesson,
        val endsIn: Duration,
        val progress: Float,
        override val validUntil: LocalDateTime?,
    ) : DayState

    /** Something non-lesson is happening: столовая, линейка, экскурсия. */
    data class DuringEvent(
        val event: SchoolEvent,
        val next: Lesson?,
        val endsIn: Duration,
        val progress: Float,
        override val validUntil: LocalDateTime?,
    ) : DayState

    /**
     * Lessons are over for today. [homeworkDay] is the next day that has
     * lessons, which is what the widget shows homework for.
     */
    data class AfterSchool(
        val finishedAt: java.time.LocalTime?,
        val homeworkDay: SchoolDay?,
        override val validUntil: LocalDateTime?,
    ) : DayState

    /** Weekend, holiday, or a date with nothing scheduled. */
    data class DayOff(
        val date: LocalDate,
        val kind: DayKind,
        val note: String?,
        val homeworkDay: SchoolDay?,
        override val validUntil: LocalDateTime?,
    ) : DayState

    /** No cached data for this date yet. */
    data class NoData(
        val date: LocalDate,
        override val validUntil: LocalDateTime? = null,
    ) : DayState
}

/** Homework the user should be looking at right now, if the state implies any. */
val DayState.homeworkFocus: SchoolDay?
    get() = when (this) {
        is DayState.AfterSchool -> homeworkDay
        is DayState.DayOff -> homeworkDay
        else -> null
    }

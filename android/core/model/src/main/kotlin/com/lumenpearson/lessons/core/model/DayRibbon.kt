package com.lumenpearson.lessons.core.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Everything that happens on one day, in the order it happens, gaps included.
 *
 * The hour ruler draws a day by *position*, which is right for a grid and
 * wrong for a ribbon somebody scrolls: a forty-minute gap becomes forty
 * minutes of blank screen that scrolls past with nothing in it. A ribbon makes
 * the gap a row of its own — «Перемена, 20 минут» — so every screenful is
 * something rather than an absence somebody has to measure with two clocks.
 *
 * Built in `:core:model` because it is the answer to «what is on this day»,
 * and that question already had a narrower answer in `:widget`
 * (`remainingTimeline`, which merges lessons and events for what is left).
 * That one is now this one, filtered — see [Ribbon.remaining] — rather than a
 * second implementation of the same sort.
 */
sealed interface RibbonEntry {

    val startsAt: LocalTime
    val endsAt: LocalTime

    /** How long the whole thing lasts, whatever it is. */
    val length: Duration get() = Duration.between(startsAt, endsAt)

    data class OfLesson(val lesson: Lesson) : RibbonEntry {
        override val startsAt: LocalTime get() = lesson.startsAt
        override val endsAt: LocalTime get() = lesson.endsAt
    }

    data class OfEvent(val event: SchoolEvent) : RibbonEntry {
        override val startsAt: LocalTime get() = event.startsAt
        override val endsAt: LocalTime get() = event.endsAt
    }

    /**
     * The gap between two lessons.
     *
     * Its own kind rather than an absence, because on this screen it is
     * something somebody plans with: twenty minutes and five minutes are two
     * different breaks, and only one of them is long enough to leave the floor.
     */
    data class OfBreak(
        override val startsAt: LocalTime,
        override val endsAt: LocalTime,
    ) : RibbonEntry
}

/**
 * Where an entry stands relative to a moment.
 *
 * @property fraction 0 before it starts, 1 once it is over, and how far
 *   through it is while it runs. A zero-length entry reads as finished rather
 *   than dividing by zero — a lesson stored with equal times is a data error,
 *   not a lesson that is eternally at its beginning.
 * @property elapsed how long it has been running; zero before it starts.
 * @property remaining how long is left; zero once it is over.
 */
data class RibbonProgress(
    val fraction: Float,
    val elapsed: Duration,
    val remaining: Duration,
) {
    val hasStarted: Boolean get() = fraction > 0f || elapsed > Duration.ZERO
    val isOver: Boolean get() = fraction >= 1f
    val isRunning: Boolean get() = hasStarted && !isOver
}

/** One day, flattened. */
data class Ribbon(val entries: List<RibbonEntry>) {

    /**
     * The entry running at [at], or null.
     *
     * The *first* match rather than the last: an event that replaces a lesson
     * shares its hour, and the event is the thing that is actually happening —
     * which is the same rule the ordering below uses, kept in one place so the
     * highlighted row and the sort cannot disagree.
     */
    fun currentAt(at: LocalTime): RibbonEntry? =
        entries.firstOrNull { at >= it.startsAt && at < it.endsAt }

    /**
     * The entry to scroll back to: what is running, else what is next.
     *
     * Null once the day is over, which is a real answer — «вернуться к
     * текущему» on a finished day would jump to the last lesson and claim it
     * is now.
     */
    fun focusAt(at: LocalTime): RibbonEntry? =
        currentAt(at) ?: entries.firstOrNull { it.startsAt > at }

    /** What is left of the day, gaps dropped: the question the widget asks. */
    fun remaining(at: LocalTime): List<RibbonEntry> =
        entries.filter { it !is RibbonEntry.OfBreak && it.endsAt > at }
}

/**
 * Builds the ribbon for [day] — lessons, the breaks between them, and events.
 *
 * Cancelled lessons are left out: the ribbon is «what is happening», and a
 * struck-through row belongs on a list of the timetable rather than in a
 * countdown. The day view that draws the whole timetable still shows them.
 *
 * Ties go to the event, for the reason the widget's own comment gives: an
 * event starting exactly when a lesson does is the thing that replaced it.
 *
 * A break is only ever between two lessons. The stretch before the first and
 * after the last is not a break, it is the rest of the day, and a row called
 * «Перемена» covering the evening would be a claim about school hours that
 * nobody made.
 */
fun ribbonOf(day: SchoolDay?): Ribbon {
    if (day == null) return Ribbon(emptyList())

    val lessons = day.activeLessons.map { RibbonEntry.OfLesson(it) }
    val events = day.events.map { RibbonEntry.OfEvent(it) }

    val breaks = lessons.zipWithNext().mapNotNull { (before, after) ->
        // Only a real gap. Back-to-back lessons have none, and a bell schedule
        // whose rows overlap would otherwise produce a break of negative
        // length — which draws as «-5 минут» and is somebody's typo, not a gap.
        if (after.startsAt > before.endsAt) {
            RibbonEntry.OfBreak(before.endsAt, after.startsAt)
        } else {
            null
        }
    }

    return Ribbon(
        (lessons + events + breaks).sortedWith(
            compareBy(
                { it.startsAt },
                {
                    when (it) {
                        is RibbonEntry.OfEvent -> 0
                        is RibbonEntry.OfLesson -> 1
                        is RibbonEntry.OfBreak -> 2
                    }
                },
            ),
        ),
    )
}

/**
 * How far through [entry] the moment [at] is.
 *
 * Takes a `LocalTime` rather than a `LocalDateTime` because a ribbon is one
 * day and the caller has already decided which; the school's own date is what
 * decides that, and it is decided where the day is chosen rather than here.
 */
fun progressOf(entry: RibbonEntry, at: LocalTime): RibbonProgress {
    val whole = entry.length
    // First, and it has to be first. Written after the two checks below it was
    // unreachable — «already over» catches the equal-times case on its way
    // past — and the *reversed* case slipped through both and answered a
    // negative `remaining`, which draws as «осталось -30 мин». Equal or
    // reversed times are a bell row somebody typed wrong; reading them as over
    // is the one answer that cannot count down to something impossible.
    if (whole <= Duration.ZERO) {
        return RibbonProgress(fraction = 1f, elapsed = Duration.ZERO, remaining = Duration.ZERO)
    }
    if (at < entry.startsAt) {
        return RibbonProgress(fraction = 0f, elapsed = Duration.ZERO, remaining = whole)
    }
    if (at >= entry.endsAt) {
        return RibbonProgress(fraction = 1f, elapsed = whole, remaining = Duration.ZERO)
    }
    val elapsed = Duration.between(entry.startsAt, at)
    return RibbonProgress(
        fraction = elapsed.toMillis().toFloat() / whole.toMillis().toFloat(),
        elapsed = elapsed,
        remaining = whole - elapsed,
    )
}

/** The same, for a caller that is holding a date and a time together. */
fun progressOf(entry: RibbonEntry, at: LocalDateTime): RibbonProgress =
    progressOf(entry, at.toLocalTime())

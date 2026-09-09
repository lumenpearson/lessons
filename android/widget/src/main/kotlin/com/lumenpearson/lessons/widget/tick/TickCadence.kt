package com.lumenpearson.lessons.widget.tick

import com.lumenpearson.lessons.core.model.DayState
import java.time.Duration
import java.time.LocalDateTime

/**
 * When the widget should next redraw itself, and how badly it needs to be on time.
 *
 * @property at the wall-clock moment to wake at.
 * @property isBoundary true when this is a real state change — a bell, the start
 *   of an event, midnight — rather than a countdown refresh. Boundaries are worth
 *   an exact, device-waking alarm because the widget would otherwise be visibly
 *   *wrong* (still saying "Урок" during a break). Countdown refreshes are not:
 *   being a minute late on "осталось 12 мин" costs nobody anything, so they are
 *   armed inexactly and without waking the device.
 */
data class WidgetTick(
    val at: LocalDateTime,
    val isBoundary: Boolean,
)

/**
 * The pure half of the widget's scheduling: no Android, no clock reads, fully
 * unit-testable.
 *
 * The problem this solves: `updatePeriodMillis` has a 30-minute floor, which is
 * useless for a countdown, and a one-minute repeating alarm is roughly 400
 * wakeups a day for a widget nobody is looking at most of the time. The answer
 * is to wake exactly as often as the *displayed text* would actually change,
 * which is a function of how much time is left.
 */
object TickCadence {

    /** Below this much time left, the countdown changes fast enough to matter. */
    val URGENT_WINDOW: Duration = Duration.ofMinutes(10)

    /** Below this, a five-minute refresh keeps the number within its own rounding. */
    val NEAR_WINDOW: Duration = Duration.ofMinutes(60)

    val URGENT_TICK: Duration = Duration.ofMinutes(1)
    val NEAR_TICK: Duration = Duration.ofMinutes(5)
    val FAR_TICK: Duration = Duration.ofMinutes(15)

    /**
     * Used only when there is nothing at all to schedule against — no timetable,
     * no boundary, no countdown. An hourly poke is cheap and gets the widget out
     * of an empty state once a sync finally lands, in the case where the
     * DATA_SYNCED broadcast was missed (process death, app standby bucket).
     */
    val IDLE_FALLBACK: Duration = Duration.ofHours(1)

    /**
     * Never arm an alarm closer than this. An alarm for "now" fires immediately,
     * re-renders, and arms another alarm for "now" — a tight loop that would
     * empty the battery in an afternoon. Fifteen seconds is far enough from any
     * rounding error at a lesson boundary to be safe.
     */
    val MIN_LEAD: Duration = Duration.ofSeconds(15)

    /**
     * The coarse countdown-refresh cadence.
     *
     * | State                                            | Time left | Next tick |
     * |--------------------------------------------------|-----------|-----------|
     * | `InLesson` / `OnBreak` / `DuringEvent` / `BeforeSchool` | ≤ 10 min  | +1 min    |
     * | `InLesson` / `OnBreak` / `DuringEvent` / `BeforeSchool` | ≤ 60 min  | +5 min    |
     * | `InLesson` / `OnBreak` / `DuringEvent` / `BeforeSchool` | > 60 min  | +15 min   |
     * | `AfterSchool`                                    | —         | none      |
     * | `DayOff`                                         | —         | none      |
     * | `NoData`                                         | —         | none      |
     * | `null` (no timetable at all)                     | —         | none      |
     *
     * The three tiers are chosen so the visible text is never stale by more than
     * one of its own units: under ten minutes the widget shows single minutes and
     * refreshes every minute; between ten and sixty it still shows minutes, but
     * being four minutes stale on "осталось 47 мин" is not something anyone can
     * see at a glance; past an hour the display is "1 ч 20 мин" and a quarter-hour
     * of drift is inside the rounding.
     *
     * "none" is not laziness: `AfterSchool` and `DayOff` display no countdown at
     * all, so nothing about them changes until the next boundary — which
     * [nextWakeUp] picks up from `validUntil` / `nextTransition` instead. Those
     * are the states the widget spends most of its life in, and they cost zero
     * wakeups.
     *
     * @return the next refresh moment, or null when this state has no countdown.
     */
    fun nextTick(state: DayState?, now: LocalDateTime): LocalDateTime? {
        val remaining = state?.countdownOrNull() ?: return null
        val step = when {
            remaining <= URGENT_WINDOW -> URGENT_TICK
            remaining <= NEAR_WINDOW -> NEAR_TICK
            else -> FAR_TICK
        }
        return now.plus(step)
    }

    /**
     * Combines the cadence tick with the real state boundaries and picks the
     * earliest.
     *
     * Three things can want the widget redrawn: the countdown getting stale
     * ([nextTick]), the schedule engine's [com.lumenpearson.lessons.core.model
     * .ScheduleEngine.nextTransition] (the next bell), and the state's own
     * `validUntil` (which is the same thing for most states, but is the only
     * source for `DayOff`/`AfterSchool`, where it points at midnight). Whichever
     * comes first wins.
     *
     * @param transition the engine's next transition, or null with no timetable.
     * @return a tick that is always strictly in the future by at least [MIN_LEAD].
     */
    fun nextWakeUp(
        state: DayState?,
        transition: LocalDateTime?,
        now: LocalDateTime,
    ): WidgetTick {
        val boundary = listOfNotNull(transition, state?.validUntil)
            .filter { it.isAfter(now) }
            .minOrNull()
        val tick = nextTick(state, now)?.takeIf { it.isAfter(now) }

        val chosen = when {
            boundary == null && tick == null -> now.plus(IDLE_FALLBACK)
            boundary == null -> tick!!
            tick == null -> boundary
            else -> minOf(boundary, tick)
        }
        // Equality goes to the boundary: if the bell and a refresh land on the
        // same second, it is the bell that must not be late.
        val isBoundary = boundary != null && chosen == boundary
        val floor = now.plus(MIN_LEAD)
        return WidgetTick(
            at = if (chosen.isBefore(floor)) floor else chosen,
            isBoundary = isBoundary,
        )
    }

    /**
     * How much time the state is counting down, or null when it is not counting
     * anything.
     *
     * A negative duration is possible when a render is late (the device was
     * dozing through the bell); it falls into the ≤ 10 min tier, which is the
     * right answer — redraw promptly.
     */
    private fun DayState.countdownOrNull(): Duration? = when (this) {
        is DayState.BeforeSchool -> startsIn
        is DayState.InLesson -> endsIn
        is DayState.OnBreak -> endsIn
        is DayState.DuringEvent -> endsIn
        is DayState.AfterSchool -> null
        is DayState.DayOff -> null
        is DayState.NoData -> null
    }
}

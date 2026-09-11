package com.lumenpearson.lessons.widget.format

import android.content.Context
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.widget.R
import java.time.Duration
import java.time.LocalTime
import java.util.Locale

/**
 * Every user-visible string the widget draws, in one place.
 *
 * Glance composables get their text from here rather than calling
 * `context.getString` inline, for two reasons: the composables stay a pure
 * function of their arguments (see `LessonsWidgetBody`), and the Russian
 * grammar — accusative weekdays, genitive months, four plural forms — is
 * concentrated in one file where it can be reviewed by someone who speaks the
 * language without reading any layout code.
 */
internal object WidgetStrings {

    /** Weekday resources are indexed by [java.time.DayOfWeek.getValue], 1..7. */
    private val WEEKDAY_ACCUSATIVE = intArrayOf(
        R.string.widget_weekday_acc_1,
        R.string.widget_weekday_acc_2,
        R.string.widget_weekday_acc_3,
        R.string.widget_weekday_acc_4,
        R.string.widget_weekday_acc_5,
        R.string.widget_weekday_acc_6,
        R.string.widget_weekday_acc_7,
    )

    /** Month resources are indexed by [java.time.LocalDate.getMonthValue], 1..12. */
    private val MONTH_GENITIVE = intArrayOf(
        R.string.widget_month_gen_1,
        R.string.widget_month_gen_2,
        R.string.widget_month_gen_3,
        R.string.widget_month_gen_4,
        R.string.widget_month_gen_5,
        R.string.widget_month_gen_6,
        R.string.widget_month_gen_7,
        R.string.widget_month_gen_8,
        R.string.widget_month_gen_9,
        R.string.widget_month_gen_10,
        R.string.widget_month_gen_11,
        R.string.widget_month_gen_12,
    )

    /**
     * The short word above the subject: "Урок", "Перемена", "Столовая"…
     *
     * This is the single most-read string in the product — on the smallest size
     * it is half of everything the widget says — so it is always one word where
     * Russian allows one.
     */
    fun stateLabel(context: Context, state: DayState): String = when (state) {
        is DayState.BeforeSchool -> context.getString(R.string.widget_state_before_school)
        is DayState.InLesson -> context.getString(R.string.widget_state_lesson)
        is DayState.OnBreak -> context.getString(R.string.widget_state_break)
        is DayState.DuringEvent -> eventLabel(context, state.event.kind)
        is DayState.AfterSchool -> context.getString(R.string.widget_state_after_school)
        is DayState.DayOff -> dayOffLabel(context, state.kind)
        is DayState.NoData -> context.getString(R.string.widget_state_no_data)
    }

    /** Same as [stateLabel] but clipped for the 2x1 size, where width is the whole problem. */
    fun stateLabelShort(context: Context, state: DayState): String = when (state) {
        is DayState.AfterSchool -> context.getString(R.string.widget_state_after_school_short)
        else -> stateLabel(context, state)
    }

    /**
     * "пн" for the week strip.
     *
     * From java.time rather than a string array, which is how the app does it
     * too: java.time already knows the correctly abbreviated weekday for the
     * device's locale, and a hand-written table gets the declensions wrong.
     */
    fun shortWeekday(date: java.time.LocalDate): String = date.dayOfWeek
        .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        .lowercase(java.util.Locale.getDefault())

    fun eventLabel(context: Context, kind: EventKind): String = context.getString(
        when (kind) {
            EventKind.CANTEEN -> R.string.widget_state_canteen
            EventKind.EXAM -> R.string.widget_state_exam
            EventKind.TRIP -> R.string.widget_state_trip
            EventKind.MEETING -> R.string.widget_state_meeting
            EventKind.EVENT -> R.string.widget_state_event
        },
    )

    /**
     * A day off is not always a weekend: [DayKind.HOLIDAY] during каникулы and
     * [DayKind.REMOTE] both reach the widget as `DayOff`, and calling каникулы
     * "выходной" is the kind of small lie that makes an app feel careless.
     */
    fun dayOffLabel(context: Context, kind: DayKind): String = context.getString(
        when (kind) {
            DayKind.HOLIDAY -> R.string.widget_state_holiday
            DayKind.REMOTE -> R.string.widget_state_remote
            DayKind.SHORTENED -> R.string.widget_state_shortened
            DayKind.NORMAL -> R.string.widget_state_day_off
        },
    )

    /**
     * A bare duration: "1 ч 20 мин", "12 мин", "< 1 мин".
     *
     * Seconds are never shown. A countdown that ticks per second would force a
     * redraw every second, and RemoteViews updates are far too expensive for
     * that; rounding *up* to the next whole minute also matches how a person
     * reads a clock — at 11:59:30 there is "1 minute" of the lesson left, not
     * zero.
     */
    fun duration(context: Context, value: Duration, short: Boolean = false): String {
        val minutesLeft = kotlin.math.ceil(value.toMillis() / 60_000.0).toLong()
        return when {
            minutesLeft <= 0L -> context.getString(
                if (short) R.string.widget_countdown_now_short else R.string.widget_countdown_now,
            )

            minutesLeft < 60L -> context.getString(R.string.widget_countdown_m, minutesLeft.toInt())

            else -> {
                val hours = (minutesLeft / 60L).toInt()
                val minutes = (minutesLeft % 60L).toInt()
                if (minutes == 0) {
                    context.getString(R.string.widget_countdown_h, hours)
                } else {
                    context.getString(R.string.widget_countdown_hm, hours, minutes)
                }
            }
        }
    }

    /** "осталось 12 мин" — used while something is running. */
    fun durationLeft(context: Context, value: Duration): String =
        context.getString(R.string.widget_countdown_left, duration(context, value))

    /** "через 12 мин" — used while waiting for something to start. */
    fun durationIn(context: Context, value: Duration): String =
        context.getString(R.string.widget_countdown_in, duration(context, value))

    /**
     * "Домашнее задание на завтра" / "…на понедельник" / "…на 15 сентября".
     *
     * [short] swaps in the "ДЗ на …" forms, which is the only way the header
     * fits on the SMALL size class without wrapping to three lines.
     */
    fun homeworkHeader(context: Context, label: HomeworkDayLabel, short: Boolean): String =
        when (label) {
            HomeworkDayLabel.Today -> context.getString(
                if (short) R.string.widget_homework_short_today else R.string.widget_homework_header_today,
            )

            HomeworkDayLabel.Tomorrow -> context.getString(
                if (short) R.string.widget_homework_short_tomorrow else R.string.widget_homework_header_tomorrow,
            )

            HomeworkDayLabel.DayAfterTomorrow -> context.getString(
                if (short) R.string.widget_homework_short_day_after else R.string.widget_homework_header_day_after,
            )

            is HomeworkDayLabel.Weekday -> context.getString(
                if (short) R.string.widget_homework_short_named else R.string.widget_homework_header_named,
                context.getString(WEEKDAY_ACCUSATIVE[label.dayOfWeek.value - 1]),
            )

            is HomeworkDayLabel.ExplicitDate -> context.getString(
                if (short) R.string.widget_homework_short_named else R.string.widget_homework_header_named,
                context.getString(
                    R.string.widget_date_day_month,
                    label.date.dayOfMonth,
                    context.getString(MONTH_GENITIVE[label.date.monthValue - 1]),
                ),
            )
        }

    /**
     * "Алгебра: §12, № 3–5", clipped to [maxChars].
     *
     * Truncation is per item rather than per widget so that a single long
     * homework entry cannot push every other subject off the widget — seeing
     * five subjects half-read beats seeing one in full.
     */
    fun homeworkLine(context: Context, item: HomeworkItem, maxChars: Int): String =
        context.getString(
            R.string.widget_homework_line,
            item.subject,
            item.text.collapseWhitespace(),
        ).ellipsize(maxChars)

    /** "5 предметов" — Russian needs one/few/many, so this goes through plurals. */
    fun subjectCount(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.widget_subject_count, count, count)

    /** "каб. 214", or null when the timetable has no room for this lesson. */
    fun room(context: Context, lesson: Lesson): String? =
        lesson.room?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.widget_room, it) }

    /**
     * "08:30" — zero-padded and always 24-hour.
     *
     * Padding matters: the LARGE timeline puts these in a left-hand column, and
     * a mix of "8:30" and "13:45" makes the column look broken. Russian schools
     * are 24-hour regardless of the device's clock preference.
     */
    fun time(value: LocalTime): String =
        String.format(Locale.ROOT, "%02d:%02d", value.hour, value.minute)

    /** "08:30–09:15". */
    fun timeRange(from: LocalTime, to: LocalTime): String = "${time(from)}–${time(to)}"

}

/**
 * Clips to [maxChars] with a real ellipsis character.
 *
 * Glance's `Text` cannot ellipsize on its own — `maxLines` hard-clips at a line
 * break with no visual hint that anything was cut — so the truncation has to
 * happen in the string. Top-level rather than a member of [WidgetStrings] so the
 * layout code can clip a subject name without importing the whole object.
 */
internal fun String.ellipsize(maxChars: Int): String {
    if (maxChars <= 1 || length <= maxChars) return this
    return take(maxChars - 1).trimEnd().trimEnd(',', ';', '.', '\u2013', '-') + "\u2026"
}

/**
 * Homework text arrives as free-form teacher input and regularly contains
 * newlines and double spaces; both would blow up a one-line row.
 */
internal fun String.collapseWhitespace(): String = trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("\\s+")

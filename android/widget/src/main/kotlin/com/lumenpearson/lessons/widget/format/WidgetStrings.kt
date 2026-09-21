package com.lumenpearson.lessons.widget.format

import android.content.Context
import androidx.annotation.StringRes
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

    /**
     * How many upper-cased characters of a bold caption the 110 dp column holds.
     *
     * The three narrow rungs are 110 dp wide, of which padding leaves about 90;
     * at [WidgetSizeClass.captionSp] of 11 sp, bold and upper-cased, that is
     * around twelve Cyrillic characters. It is a budget rather than a
     * measurement because Glance has no way to ask — and no way to ellipsize
     * either, which is what makes going over it a hard clip mid-word rather
     * than an «…».
     */
    const val NARROW_LABEL_CHARS = 12

    /**
     * The state label for the card the widget shows once lessons are over.
     *
     * Its own function, and only for that card, because it is the only place a
     * state label meets a narrow column. Everywhere else the label sits beside a
     * countdown on a layout at least [WidgetSizeClass.MEDIUM] wide, or the
     * narrow layout takes the homework branch and draws «ДЗ на завтра» instead —
     * which is exactly why the old blanket `stateLabelShort` was removed as
     * unreachable. `RestDayBody` is the path that reading missed: `StackBody`
     * serves `SMALL_TALL` and `NARROW` too, so «УРОКИ ЗАКОНЧИЛИСЬ» and
     * «СОКРАЩЁННЫЙ ДЕНЬ» were drawn into 90 dp and clipped mid-word.
     *
     * The short forms keep the claim rather than trading it for a shorter one:
     * «Закончились» still says the lessons ended, where the «Уроков нет» the
     * removed version used says there were none. The other four rest labels are
     * already inside the budget and are not given a second spelling for the sake
     * of symmetry.
     */
    fun restStateLabel(context: Context, state: DayState, narrow: Boolean): String =
        shortStateLabelRes(state, narrow)?.let(context::getString) ?: stateLabel(context, state)

    /**
     * Which shorter spelling [restStateLabel] reaches for, or null for the full one.
     *
     * Split out so the choice can be checked without a `Context`: this module's
     * unit tests run with `isReturnDefaultValues`, where `getString` answers
     * nothing at all, and they read `res/values/` out of the source tree
     * instead. A resource id is an `Int` and needs neither.
     */
    @StringRes
    internal fun shortStateLabelRes(state: DayState, narrow: Boolean): Int? = when {
        !narrow -> null
        state is DayState.AfterSchool -> R.string.widget_state_after_school_short
        state is DayState.DayOff && state.kind == DayKind.SHORTENED ->
            R.string.widget_state_shortened_short
        // «САМОПОДГОТОВКА» is fourteen characters and the column holds about
        // twelve. `NarrowLabelBudgetTest` is what said so, on the build that
        // added the kind rather than on a phone months later.
        state is DayState.DayOff && state.kind == DayKind.SELF_STUDY ->
            R.string.widget_state_self_study_short
        else -> null
    }

    /**
     * "пн" for the week strip.
     *
     * From java.time rather than a string array, which is how the app does it
     * too: java.time already knows the correctly abbreviated weekday for the
     * chosen locale, and a hand-written table gets the declensions wrong.
     *
     * The locale comes from [context] rather than from `Locale.getDefault()`,
     * which is the one difference between this and the app's own version and
     * the reason it takes a context at all. Below Android 13 the app's chosen
     * language is a property of a `Context` and the process default is still
     * the phone's — so an English widget drew six English words and then "пн
     * вт ср" underneath them. On 33+ the two agree, because the platform sets
     * the process default too.
     */
    fun shortWeekday(context: Context, date: java.time.LocalDate): String {
        val locale = locale(context)
        return date.dayOfWeek
            .getDisplayName(java.time.format.TextStyle.SHORT, locale)
            .lowercase(locale)
    }

    /** What [context] resolves resources through; the phone's own as a fallback. */
    private fun locale(context: Context): Locale =
        context.resources.configuration.locales.get(0) ?: Locale.getDefault()

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
     * A day off is not always a weekend: [DayKind.HOLIDAY] during the holidays
     * and [DayKind.REMOTE] both reach the widget as `DayOff`, and calling a holiday
     * "выходной" is the kind of small lie that makes an app feel careless.
     */
    fun dayOffLabel(context: Context, kind: DayKind): String = context.getString(
        when (kind) {
            DayKind.HOLIDAY -> R.string.widget_state_holiday
            DayKind.REMOTE -> R.string.widget_state_remote
            DayKind.SHORTENED -> R.string.widget_state_shortened
            DayKind.SELF_STUDY -> R.string.widget_state_self_study
            DayKind.DAY_OFF -> R.string.widget_state_day_off_given
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
        val minutesLeft = minutesShown(value)
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

    /**
     * The number of minutes [duration] will actually print for [value].
     *
     * Rounded **up**, because that is how a person reads a clock: at 11:59:30
     * there is a minute of the lesson left, not none. Exposed rather than left
     * inside [duration] because anything that reasons about "how long is left"
     * has to reason about the figure on the screen and not about the duration
     * behind it — the colour did not, and `Duration.toMinutes()` truncates
     * where this rounds up, so at five minutes and forty seconds the widget
     * drew «6 мин» in the error colour under a rule documented as "the last
     * five minutes".
     */
    fun minutesShown(value: Duration): Long =
        kotlin.math.ceil(value.toMillis() / 60_000.0).toLong()

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
        homeworkLine(context, item.subject, item.text, maxChars)

    /** @see homeworkLine */
    fun homeworkLine(context: Context, subject: String, text: String, maxChars: Int): String =
        context.getString(
            R.string.widget_homework_line,
            subject,
            text.collapseWhitespace(),
        ).ellipsize(maxChars)

    /** "5 предметов" — Russian needs one/few/many, so this goes through plurals. */
    fun subjectCount(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.widget_subject_count, count, count)

    /**
     * The word beside the ticking figure.
     *
     * The `Chronometer` can only draw digits, and "05:57" on its own reads as
     * five minutes to six. It needs a word, and which word depends on whether
     * something is running or something is coming.
     */
    fun untilBell(context: Context): String = context.getString(R.string.widget_until_bell)

    /** @see untilBell */
    fun untilStart(context: Context): String = context.getString(R.string.widget_until_start)

    /** "из 40 мин" — how long the thing that is running lasts in total. */
    fun ofMinutes(context: Context, minutes: Int): String =
        context.getString(R.string.widget_of_minutes, minutes)

    /** "перемена 20 мин" — the length of the gap, which is what decides the plan for it. */
    fun breakLength(context: Context, minutes: Int): String =
        context.getString(R.string.widget_break_length, minutes)

    /** Joins the pieces of the meta line, skipping the ones that are absent. */
    fun meta(context: Context, vararg parts: String?): String? {
        val kept = parts.filterNotNull().filter { it.isNotBlank() }
        if (kept.isEmpty()) return null
        return kept.joinToString(" ${context.getString(R.string.widget_meta_separator)} ")
    }

    /** "6 уроков" — Russian needs one/few/many, so this goes through plurals. */
    fun lessonCount(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.widget_lesson_count, count, count)

    /** "6 уроков · Алгебра в 09:00". */
    fun dayPlan(context: Context, lessons: Int, firstSubject: String, firstAt: LocalTime): String =
        context.getString(
            R.string.widget_next_day_summary,
            lessonCount(context, lessons),
            firstSubject,
            time(firstAt),
        )

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
    // One back if the cut landed between the halves of a surrogate pair. A
    // `Char` is a UTF-16 code unit, not a character, so an emoji straddling the
    // boundary otherwise leaves its leading half behind \u2014 which `trimEnd` does
    // not consider whitespace and the widget draws as a tofu box.
    val end = (maxChars - 1).let { if (this[it - 1].isHighSurrogate()) it - 1 else it }
    return take(end).trimEnd().trimEnd(',', ';', '.', '\u2013', '-') + "\u2026"
}

/**
 * Homework text arrives as free-form teacher input and regularly contains
 * newlines and double spaces; both would blow up a one-line row.
 */
internal fun String.collapseWhitespace(): String = trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("\\s+")

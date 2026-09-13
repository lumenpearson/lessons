package com.lumenpearson.lessons.core.model

/**
 * The choices that only change how the app looks and feels.
 *
 * They live in `:core:model` rather than next to the rest of the settings
 * because three modules need to name them: `:core:data` stores them,
 * `:core:designsystem` acts on them, and `:app` draws the pickers. A shared
 * enum is the cheapest way to keep those three honest about the same set of
 * values.
 *
 * The set is modelled on the "Customizations" section of
 * [Essentials](https://github.com/sameerasw/essentials), which is where this
 * app's design language comes from.
 */

/** Light, dark, or whatever the system is doing. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Which of the two shipped languages the app is read in.
 *
 * Deliberately not a locale string: the app ships exactly the locales named in
 * `localeFilters` (`ru` and `en`), and a free-form tag would let a picker offer
 * a language whose strings are not in the APK — which on Android does not fail,
 * it silently falls back to Russian, one string at a time.
 *
 * [SYSTEM] carries an empty [tag] on purpose: it is the absence of a per-app
 * locale, which is how both the platform (an empty `LocaleList`) and the
 * pre-33 path (no configuration override) spell "whatever the phone is set to".
 */
enum class AppLanguage(val tag: String) {
    /** Follow the phone; anything that is neither Russian nor English lands on Russian. */
    SYSTEM(""),

    /** The language the app is written in: `values/` is Russian. */
    RUSSIAN("ru"),

    /** The translation in `values-en/`. */
    ENGLISH("en"),
    ;

    companion object {
        fun fromName(name: String?): AppLanguage = entries.firstOrNull { it.name == name } ?: SYSTEM

        /**
         * The entry a BCP-47 tag belongs to, [SYSTEM] for an empty or unknown
         * one. Only the language subtag is compared, so `en-GB` — which a phone
         * may well report — is English rather than a fourth, unshipped answer.
         */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.lowercase().orEmpty()
            return entries.firstOrNull { it != SYSTEM && it.tag == language } ?: SYSTEM
        }
    }
}

/**
 * How hard the app taps back.
 *
 * The levels of `HapticFeedbackType` from Essentials, minus its `TICK` — on that
 * enum `TICK` is a duplicate of `SUBTLE` on every API level this app supports,
 * and an option indistinguishable from the one above it is not a setting.
 */
enum class HapticStrength {
    /** Off. The app never vibrates, not even on tab changes. */
    NONE,

    /** A single predefined tick: the Essentials default. */
    SUBTLE,

    /** Two quick taps; reads like a physical switch. */
    DOUBLE,

    /** A firmer double, for people who cannot feel [SUBTLE] through a case. */
    CLICK,
    ;

    companion object {
        fun fromName(name: String?): HapticStrength =
            entries.firstOrNull { it.name == name } ?: SUBTLE
    }
}

/**
 * The tabs of the bottom bar, in bar order.
 *
 * Duplicated neither in the navigation graph nor in the settings picker: both
 * read this list, so "default tab" cannot offer a destination the bar does not
 * have.
 *
 * Settings is not among them. It used to be, and it does not belong: the other
 * three are places you live in and switch between, settings is somewhere you go,
 * change one thing and come back from. In the bar it sat next to them as a peer,
 * took a quarter of the pill's width from screens that need it, and meant the
 * "default tab" picker could open the app on the preferences. It is now the
 * detached button beside the pill, which is where Essentials puts its own.
 *
 * [fromName] falls back to [TODAY], so a device that stored `SETTINGS` as its
 * default tab before this change reads back a destination that exists.
 */
enum class HomeTab {
    TODAY,
    WEEK,
    HOMEWORK,
    ;

    companion object {
        fun fromName(name: String?): HomeTab = entries.firstOrNull { it.name == name } ?: TODAY
    }
}

/**
 * Which typeface the app is set in.
 *
 * Two entries and no more, because the choice a person actually has is between
 * "the app's own look" and "the one my phone is already set to". A font picker
 * over everything installed would be a different feature — and on Android it is
 * not a list the app can read anyway.
 *
 * [SYSTEM] exists for the people the bundled face is wrong for: a vendor's own
 * face is what the rest of their phone reads in, and somebody who has set a
 * legibility font at the system level has already answered this question once.
 */
enum class AppFont {
    /** Google Sans Flex, bundled with the app. */
    BUNDLED,

    /** Whatever the device's own sans-serif is. */
    SYSTEM,
    ;

    companion object {
        fun fromName(name: String?): AppFont = entries.firstOrNull { it.name == name } ?: BUNDLED
    }
}

/**
 * How much of a lesson the "скоро урок" notification spells out.
 *
 * The notification is read on a lock screen, one-handed, while packing a bag,
 * and the two useful answers are far apart: the subject alone is the fastest
 * thing to read, and subject-with-room-and-teacher is what somebody in a school
 * where the room moves every week actually needs. Anything between the two is
 * a longer line that answers neither.
 */
enum class LessonAlertDetail {
    /** "Алгебра" — and nothing else. */
    SUBJECT,

    /** "Алгебра · каб. 214 · Иванова М. П." */
    FULL,
    ;

    companion object {
        /**
         * [FULL] rather than [SUBJECT], because it is the closest thing to what
         * the notification already said before there was a choice: subject and
         * room. A default that shortened everybody's existing notification
         * would be this setting taking something away on the way in.
         */
        fun fromName(name: String?): LessonAlertDetail =
            entries.firstOrNull { it.name == name } ?: FULL
    }
}

/**
 * In what order the home screen stacks its two big blocks.
 *
 * [AUTOMATIC] is the behaviour the screen has always had and the one the widget
 * follows: while school is on, the timetable leads; once the last bell has rung,
 * homework is what the screen is about. It is right for most people most days,
 * and wrong for the two who know exactly what they open the app for — a pupil
 * who only ever checks the next lesson, and one who only ever checks what is
 * set. Those two get to say so instead of reading past a block that moves.
 */
enum class TodayLayout {
    /** Homework leads once the school day is over, and not before. */
    AUTOMATIC,

    /** The timetable always leads, even at ten at night. */
    LESSONS_FIRST,

    /** Homework always leads, even during the second lesson. */
    HOMEWORK_FIRST,
    ;

    /**
     * @param schoolIsOver the last bell has rung, or it is a day off — which is
     *   the only thing [AUTOMATIC] looks at.
     */
    fun homeworkLeads(schoolIsOver: Boolean): Boolean = when (this) {
        AUTOMATIC -> schoolIsOver
        LESSONS_FIRST -> false
        HOMEWORK_FIRST -> true
    }

    companion object {
        fun fromName(name: String?): TodayLayout =
            entries.firstOrNull { it.name == name } ?: AUTOMATIC
    }
}

/**
 * Which date the calendar's week strip begins on.
 *
 * [TODAY] is not a different first weekday, it is a different idea of a week:
 * seven days forward from wherever you are. A pupil looking at their phone on
 * Friday afternoon is asking about Monday, and a Monday-aligned strip answers
 * that by putting the four days they have already lived through in front of it.
 *
 * The month grid ignores this and stays Monday-aligned: its rows are seven
 * columns wide by construction, and a grid whose first column moved with the
 * date would be a different shape every day.
 */
enum class WeekStart {
    /** The calendar week, as a Russian school timetable is printed. */
    MONDAY,

    /** A rolling seven days, starting with the date in view. */
    TODAY,
    ;

    companion object {
        fun fromName(name: String?): WeekStart = entries.firstOrNull { it.name == name } ?: MONDAY
    }
}

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
 */
enum class HomeTab {
    TODAY,
    WEEK,
    HOMEWORK,
    SETTINGS,
    ;

    companion object {
        fun fromName(name: String?): HomeTab = entries.firstOrNull { it.name == name } ?: TODAY
    }
}

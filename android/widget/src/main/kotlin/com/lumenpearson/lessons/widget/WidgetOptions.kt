package com.lumenpearson.lessons.widget

/**
 * The parts of `AppSettings` the widget actually renders differently for.
 *
 * The widget takes this instead of `AppSettings` itself so that the layout code
 * has no dependency on `:core:data` at all: the composables can be exercised
 * from a test or a preview by constructing three booleans, and a new field in
 * `AppSettings` cannot silently change what the widget draws.
 *
 * @property showProgress draw the progress bar for the running lesson/break.
 *   Some users read it as clutter; it is also the first thing to go on a
 *   40dp-tall widget regardless of the setting.
 * @property showTeacher append the teacher's name to timeline rows. Off by
 *   default because on a phone-width widget it costs the room number.
 * @property showRoom append "каб. N" to timeline rows. Always on today, but kept
 *   explicit so the room can be dropped when the teacher is shown.
 */
data class WidgetOptions(
    val showProgress: Boolean = true,
    val showTeacher: Boolean = false,
    val showRoom: Boolean = true,
)

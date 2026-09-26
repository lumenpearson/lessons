package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.data.repository.ShellMode

/**
 * Which home [HomeShell][LessonsApp] draws: the class's tabs, or the diary.
 *
 * One shell for both, branching in the few places the two differ — the pages,
 * the toolbar's items, where back goes, what a widget's date does — rather
 * than a second shell, which would be a second copy of the layer and back rules
 * that `ShellBackTest` holds.
 */
enum class ShellHome {
    /** Today, Week and Homework: a phone in a class. */
    TIMETABLE,

    /** The diary itself: a phone whose only account is a diary. */
    DIARY,
}

/**
 * The home for [mode], or `null` when there is none to draw — [ShellMode.NONE],
 * which the root gate sends to the way in before this is ever asked.
 *
 * The shell is keyed on the answer, so a change of home — joining a class from
 * the diary, or leaving the last class with a diary signed in — resets its
 * hoisted state: the settings layer closes and the phone lands on the new home
 * rather than inside a section of the old one.
 */
fun shellHome(mode: ShellMode): ShellHome? = when (mode) {
    ShellMode.CLASS -> ShellHome.TIMETABLE
    ShellMode.DIARY -> ShellHome.DIARY
    ShellMode.NONE -> null
}

package com.lumenpearson.lessons.navigation

/** The five things one back press can do in the signed-in shell, and «nothing». */
internal enum class ShellBack {
    /** Leave the mode where the tabs are being arranged; nothing else moves. */
    LEAVE_ARRANGING,

    /** Leave the documentation, and the settings tree it was opened from. */
    CLOSE_DOCS,

    /** Close one settings section, leaving the settings root behind it. */
    CLOSE_SECTION,

    /** Close the settings root, back onto the tabs. */
    CLOSE_SETTINGS,

    /** Return to the default tab — the predictive gesture, which is animated. */
    HOME,

    /** Nothing of ours: the press leaves the app. */
    SYSTEM,
}

/**
 * What one back press does, given where the shell is.
 *
 * Stated once and separately because the shell registers a handler per layer,
 * and «enabled» on four handlers is one rule written four times. It was written
 * four times, and the conditions had to be read against each other to see that
 * they were exclusive: the documentation is opened from inside a settings
 * section, so while it is up two of the others are true as well.
 *
 * The arranging mode is first, above every page, and that is the whole of its
 * claim. It can only be armed on the tabs, so the pages below it are closed
 * whenever it is open — but the *pager* is not: a reader who is arranging the
 * bar on a tab that is not their default would otherwise have their back press
 * taken by the predictive gesture, which would animate them home with the bar
 * still wobbling; and on the default tab it would leave the app outright.
 */
internal fun shellBack(
    arranging: Boolean,
    docsOpen: Boolean,
    sectionOpen: Boolean,
    settingsOpen: Boolean,
    onHomePage: Boolean,
): ShellBack = when {
    arranging -> ShellBack.LEAVE_ARRANGING
    docsOpen -> ShellBack.CLOSE_DOCS
    sectionOpen -> ShellBack.CLOSE_SECTION
    settingsOpen -> ShellBack.CLOSE_SETTINGS
    !onHomePage -> ShellBack.HOME
    else -> ShellBack.SYSTEM
}

package com.lumenpearson.lessons.navigation

/**
 * Where the shell is, as coarsely as the action button needs to know.
 *
 * Not the same thing as the shell's own page: the settings root and a settings
 * section are one destination here, because the button beside the pill is the
 * same on both. Keeping this coarse is what stops the rule below from growing a
 * branch every time a page is added.
 */
internal enum class ShellDestination {
    TABS,
    SETTINGS,
    DOCS,
}

/** The four things the toolbar's one action slot can be. */
internal enum class ShellActionKind {
    /** The way into settings, which is why settings is not a tab. */
    SETTINGS,

    /** Crash reports, and the switch that decides whether any are kept. */
    DEBUG,

    /** One step back through the documentation, and out of it at the end. */
    BACK,

    /** No button at all. */
    NONE,
}

/**
 * Which button the toolbar carries, given where the shell is.
 *
 * Pulled out of the composable that builds the button so that it can be stated
 * once and checked: the slot holds exactly one thing, three screens compete for
 * it, and the rule that settles that is the sort of thing that is rewritten by
 * accident when a fourth arrives.
 *
 * [NONE][ShellActionKind.NONE] rather than a disabled button for a reader with
 * no debug page: on a toolbar with room for exactly one action, a button that
 * refuses is worse than a gap.
 *
 * @param manager whether the server has said this phone belongs to an
 *   administrator or the owner of the class.
 */
internal fun shellActionKind(
    destination: ShellDestination,
    manager: Boolean,
): ShellActionKind = when (destination) {
    // Back wins over everything on the documentation, and it is the only
    // screen whose action is navigation: the pill there is the table of
    // contents, so the way out has nowhere else to live.
    ShellDestination.DOCS -> ShellActionKind.BACK
    ShellDestination.TABS -> ShellActionKind.SETTINGS
    ShellDestination.SETTINGS -> if (manager) ShellActionKind.DEBUG else ShellActionKind.NONE
}

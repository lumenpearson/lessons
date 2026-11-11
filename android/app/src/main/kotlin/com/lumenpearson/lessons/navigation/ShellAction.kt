package com.lumenpearson.lessons.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.ToolbarAction
import com.lumenpearson.lessons.core.designsystem.text.correctedString

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

/**
 * The button beside the pill, chosen by where the shell is.
 *
 * On the tabs it is the way into settings, which is why settings stopped being a
 * tab. Inside settings it is the bug — the crash reports and the switch that
 * decides whether any are kept — which is where Essentials puts its own bug
 * button and for the same reason: this app ships as an APK inside one school,
 * with no crash service behind it, so the only place a crash can be read is the
 * phone it happened on.
 *
 * It used to be a refresh button here. Refresh already has a row of its own in
 * the sync section, two taps away, and spending the one permanent button in the
 * app on a duplicate of a row is a poor trade.
 *
 * On the documentation it is the way back, because the pill beside it is full:
 * it carries the sections, so the bar's standard mode — a back arrow and a
 * title — is not available there. Which of the three it is, is decided by
 * [shellActionKind]; this only dresses the answer.
 */
@Composable
internal fun shellAction(
    destination: ShellDestination,
    manager: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDebug: (at: Offset) -> Unit,
    onBack: () -> Unit,
): ToolbarAction? = when (shellActionKind(destination, manager)) {
    ShellActionKind.SETTINGS -> ToolbarAction(
        icon = Icons.Rounded.Settings,
        contentDescription = correctedString(R.string.nav_settings),
        onClick = { onOpenSettings() },
    )

    ShellActionKind.DEBUG -> ToolbarAction(
        icon = Icons.Rounded.BugReport,
        contentDescription = correctedString(R.string.debug_open),
        onClick = onOpenDebug,
    )

    ShellActionKind.BACK -> ToolbarAction(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = correctedString(R.string.docs_back),
        onClick = { onBack() },
    )

    ShellActionKind.NONE -> null
}

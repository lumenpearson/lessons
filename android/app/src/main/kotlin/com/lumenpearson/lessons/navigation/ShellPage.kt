package com.lumenpearson.lessons.navigation

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.settings.SettingsSection

/**
 * Where the shell is, and how deep.
 *
 * The depth is declared rather than taken from an enum's ordinal, because an
 * ordinal is a declaration order and reordering the list would silently reverse
 * a transition.
 */
@Immutable
internal sealed interface ShellPage {

    val depth: Int

    data object Tabs : ShellPage {
        override val depth: Int = 0
    }

    data object SettingsRoot : ShellPage {
        override val depth: Int = 1
    }

    /**
     * A settings page, [level] pages above the root: 0 for one opened from the
     * root, 1 for one opened from that, and so on (#243). [parent] is the page
     * under it, null when that is the root — which is what the back pill names
     * (#244). Deeper by its level, so a page opened from a page slides forward
     * and back from it slides back.
     */
    data class Section(
        val section: SettingsSection,
        val parent: SettingsSection? = null,
        val level: Int = 0,
    ) : ShellPage {
        override val depth: Int = 2 + level
    }

    /**
     * The guide, all of it.
     *
     * One destination rather than one per section, which is the change: the
     * sections are peers on a pager and moving between them is a swipe, so the
     * shell's `AnimatedContent` has nothing to do between them. It used to push
     * a screen per section, with a depth per section to make the slide follow
     * the finger; a pager does that itself, in the same gesture the home tabs
     * use, and without a history to walk back out of.
     *
     * Deeper than any settings section can be, so opening the guide still slides
     * forward and leaving it slides back, from whichever page it was opened.
     */
    data object Docs : ShellPage {
        override val depth: Int = Int.MAX_VALUE
    }
}

/** The page as the developer mode's activity record names it: technical, and never shown to anybody else. */
internal fun screenName(page: ShellPage, tab: HomeTab?): String = when (page) {
    ShellPage.Tabs -> "tab ${tab?.name ?: "none"}"
    ShellPage.SettingsRoot -> "settings"
    is ShellPage.Section -> "settings/${page.section.name}"
    ShellPage.Docs -> "guide"
}

/** As much of the page as the toolbar's action button needs to know. */
internal val ShellPage.destination: ShellDestination
    get() = when (this) {
        ShellPage.Tabs -> ShellDestination.TABS
        ShellPage.SettingsRoot, is ShellPage.Section -> ShellDestination.SETTINGS
        ShellPage.Docs -> ShellDestination.DOCS
    }

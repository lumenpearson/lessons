package com.lumenpearson.lessons.navigation

import androidx.compose.runtime.Immutable
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

    data class Section(val section: SettingsSection) : ShellPage {
        override val depth: Int = 2
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
     * Deeper than a settings section, so opening the guide still slides forward
     * and leaving it slides back.
     */
    data object Docs : ShellPage {
        override val depth: Int = 3
    }
}

/** As much of the page as the toolbar's action button needs to know. */
internal val ShellPage.destination: ShellDestination
    get() = when (this) {
        ShellPage.Tabs -> ShellDestination.TABS
        ShellPage.SettingsRoot, is ShellPage.Section -> ShellDestination.SETTINGS
        ShellPage.Docs -> ShellDestination.DOCS
    }

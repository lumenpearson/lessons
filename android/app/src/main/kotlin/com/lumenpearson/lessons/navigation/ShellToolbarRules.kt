package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.designsystem.theme.ScrollOffsetHolder
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.settings.SettingsSection

/** Read only the pager drawn by this destination, including during page transitions. */
internal inline fun <T> ShellPage.toolbarContent(tabs: () -> T, docs: () -> T, other: () -> T): T =
    when (this) {
        ShellPage.Tabs -> tabs()
        ShellPage.Docs -> docs()
        ShellPage.SettingsRoot, is ShellPage.Section -> other()
    }

internal fun ShellPage.toolbarBack(closeSettings: () -> Unit, closeSection: () -> Unit): (() -> Unit)? =
    when (this) {
        ShellPage.Tabs, ShellPage.Docs -> null
        ShellPage.SettingsRoot -> closeSettings
        is ShellPage.Section -> closeSection
    }

internal inline fun ShellPage.shellOffset(
    tabs: () -> ScrollOffsetHolder,
    settings: ScrollOffsetHolder,
    section: (SettingsSection) -> ScrollOffsetHolder,
    docs: () -> ScrollOffsetHolder,
): ScrollOffsetHolder = when (this) {
    ShellPage.Tabs -> tabs()
    ShellPage.SettingsRoot -> settings
    is ShellPage.Section -> section(this.section)
    ShellPage.Docs -> docs()
}

/** After a reorder the pager can still name the old index (#180). Otherwise follow the target page (#260). */
internal inline fun timetableToolbarIndex(kept: HomeTab?, displayed: List<HomeTab>, target: () -> HomeTab?): Int =
    (kept ?: target())?.let(displayed::indexOf) ?: -1

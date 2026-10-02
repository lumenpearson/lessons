package com.lumenpearson.lessons.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.ui.settings.SettingsSection

/**
 * The settings pages open on top of the root, in the order they were opened.
 *
 * A path rather than the one open section it replaced (#243). With one, a page
 * opened from a page — «Разрешения» from «Уведомления» — overwrote it, and back
 * had nothing left to return to but the root. Each page is opened *from*
 * somewhere, and back closes exactly the last one.
 */
@Immutable
internal data class SettingsTrail(val sections: List<SettingsSection> = emptyList()) {

    /** The page in front, or null when the root is. */
    val top: SettingsSection? get() = sections.lastOrNull()

    /**
     * [section] opened from [from]: the root when [from] is null, a page on the
     * path otherwise.
     *
     * Everything after [from] is dropped first. A page that slides out is still
     * composed for the length of the slide, so a tap on its row can arrive from
     * a page that is no longer the top; it opens from where the reader saw it,
     * not on top of whatever came in. A [from] no longer on the path at all is
     * treated the same way, as the root.
     */
    fun opened(section: SettingsSection, from: SettingsSection?): SettingsTrail {
        val base = from?.let { sections.indexOf(it) }?.takeIf { it >= 0 }
            ?.let { sections.take(it + 1) }
            .orEmpty()
        return SettingsTrail(base + section)
    }

    /** One page closed: the one in front. */
    fun closed(): SettingsTrail = SettingsTrail(sections.dropLast(1))

    /** The page in front as the shell draws it, with what is under it. */
    fun page(): ShellPage.Section? = top?.let { section ->
        ShellPage.Section(
            section = section,
            parent = sections.getOrNull(sections.lastIndex - 1),
            level = sections.lastIndex,
        )
    }

    /** As saved across process death: the names, in order, comma-separated. */
    fun encode(): String = sections.joinToString(Separator) { it.name }

    companion object {

        private const val Separator = ","

        /**
         * The path [encoded] by [encode]. A name this build no longer has —
         * a section removed by an update — is dropped with everything after it,
         * so the reader lands on the deepest page that still exists.
         */
        fun decode(encoded: String?): SettingsTrail {
            if (encoded.isNullOrEmpty()) return SettingsTrail()
            val names = encoded.split(Separator)
            val known = names.map(SettingsSection::fromName)
            val upTo = known.indexOfFirst { it == null }.let { if (it < 0) known.size else it }
            return SettingsTrail(known.take(upTo).filterNotNull())
        }
    }
}

/**
 * What the back pill says: where back goes, not where the reader is (#244).
 *
 * The page's own name is already its heading, a hand's width above the pill;
 * the pill said it a second time and never said what the arrow beside it would
 * do. On a section it names the page under it — another section, or the root's
 * «Настройки» — and on the root the tab it was opened from, which [tabLabel]
 * carries because the shell knows it and a page does not. Null where the bar has
 * no pill of this kind.
 */
@StringRes
internal fun backLabel(page: ShellPage, @StringRes tabLabel: Int): Int? = when (page) {
    ShellPage.Tabs, ShellPage.Docs -> null
    ShellPage.SettingsRoot -> tabLabel
    is ShellPage.Section -> page.parent?.titleRes ?: R.string.settings_title
}

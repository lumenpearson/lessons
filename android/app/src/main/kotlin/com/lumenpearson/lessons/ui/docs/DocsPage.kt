package com.lumenpearson.lessons.ui.docs

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.R

/**
 * One piece of a documentation page.
 *
 * The guide could have been four long strings, one per page, and that is the
 * shape this deliberately is not. Three things fall out of splitting it:
 * a paragraph is the unit a translator works in, so `strings_docs.xml` is a
 * list of sentences rather than four walls of text with newlines baked into
 * them; the renderer decides what a list or a step *looks* like once, instead
 * of every page agreeing to spell bullets the same way; and a search over the
 * documentation — which this app does not have yet — has something to return a
 * hit *in*, rather than a page and a scroll position to guess at.
 *
 * Every block carries string resource ids and nothing else — no drawable, no
 * composable, no `ImageVector`. That is what lets the whole of the content be
 * read by a plain JVM test, and it keeps the copy itself in the XML where the
 * translation test can see it.
 */
@Immutable
sealed interface DocsBlock {

    /** A run of prose. The default, and what most of the guide is. */
    data class Paragraph(@param:StringRes val textRes: Int) : DocsBlock

    /**
     * Several things of the same kind, each worth finding on its own — the four
     * notification triggers, the four roles, the eight management pages.
     *
     * @param itemsRes one string resource per point, in reading order.
     */
    data class Points(val itemsRes: List<Int>) : DocsBlock

    /**
     * An aside the reader needs but did not ask for: a caveat, a version
     * difference, a promise about a password.
     *
     * Drawn apart from the prose because that is exactly what it is — the
     * sentence somebody skimming must not skim past.
     */
    data class Note(@param:StringRes val textRes: Int) : DocsBlock

    /**
     * One numbered stage of something done in order.
     *
     * The number is declared rather than taken from the block's position in the
     * list, because the list is also where paragraphs and notes live: a step
     * that counted itself would start again from one the moment a sentence was
     * added between two steps.
     */
    data class Step(
        val number: Int,
        @param:StringRes val titleRes: Int,
        @param:StringRes val textRes: Int,
    ) : DocsBlock
}

/**
 * The documentation, as a list of pages.
 *
 * One entry per section of `docs/guide.md`, in the order somebody meeting the
 * app meets them: install it, look at it, put it on the home screen, make it
 * talk, then the three things only some people need. Declaration order is what
 * the toolbar shows, so it is reading order and not alphabetical.
 *
 * The enum holds resource ids and blocks and nothing else — the icon each page
 * shows in the toolbar is a rendering decision and lives with the rendering,
 * because an enum that built an `ImageVector` in its constructor could not be
 * touched by a test that never starts Compose.
 *
 * @param titleRes the page's own heading.
 * @param labelRes the short name the floating toolbar carries. Separate from
 *   [titleRes] because the bar gives a label 80 dp and a heading gets a line:
 *   «Управление классом» is the right heading and the wrong pill.
 * @param summaryRes one line saying what the page answers, under the heading.
 */
enum class DocsPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val labelRes: Int,
    @param:StringRes val summaryRes: Int,
) {
    START(R.string.docs_start_title, R.string.docs_start_label, R.string.docs_start_summary),
    TABS(R.string.docs_tabs_title, R.string.docs_tabs_label, R.string.docs_tabs_summary),
    WIDGET(R.string.docs_widget_title, R.string.docs_widget_label, R.string.docs_widget_summary),
    ALERTS(R.string.docs_alerts_title, R.string.docs_alerts_label, R.string.docs_alerts_summary),
    LANGUAGE(
        R.string.docs_language_title,
        R.string.docs_language_label,
        R.string.docs_language_summary,
    ),
    TELEGRAM(
        R.string.docs_telegram_title,
        R.string.docs_telegram_label,
        R.string.docs_telegram_summary,
    ),
    DIARY(R.string.docs_diary_title, R.string.docs_diary_label, R.string.docs_diary_summary),
    ADMIN(R.string.docs_admin_title, R.string.docs_admin_label, R.string.docs_admin_summary),
    ;

    /** What the page says, in order. Built once; see [DocsContent]. */
    val blocks: List<DocsBlock> get() = DocsContent.getValue(this)

    companion object {

        /** The page this enum's `name` addresses, or `null` for anything else. */
        fun fromName(name: String?): DocsPage? = entries.firstOrNull { it.name == name }

        /** The page the documentation opens on when it is entered from settings. */
        val First: DocsPage = entries.first()
    }
}

/**
 * Every page's blocks, built once for the process.
 *
 * A `when` behind a property would be re-evaluated — and re-allocated — on
 * every recomposition of a scrolling list, which is the one place in this
 * feature where that is measurable. The content never changes at runtime, so
 * it is built lazily and then read.
 */
private val DocsContent: Map<DocsPage, List<DocsBlock>> by lazy {
    DocsPage.entries.associateWith(::blocksOf)
}

private fun blocksOf(page: DocsPage): List<DocsBlock> = when (page) {
    DocsPage.START -> listOf(
        DocsBlock.Paragraph(R.string.docs_start_intro),
        DocsBlock.Paragraph(R.string.docs_start_steps_intro),
        DocsBlock.Step(
            1,
            R.string.docs_start_step_welcome_title,
            R.string.docs_start_step_welcome_text,
        ),
        DocsBlock.Step(
            2,
            R.string.docs_start_step_about_title,
            R.string.docs_start_step_about_text,
        ),
        DocsBlock.Step(
            3,
            R.string.docs_start_step_preferences_title,
            R.string.docs_start_step_preferences_text,
        ),
        DocsBlock.Step(
            4,
            R.string.docs_start_step_permissions_title,
            R.string.docs_start_step_permissions_text,
        ),
        DocsBlock.Step(
            5,
            R.string.docs_start_step_join_title,
            R.string.docs_start_step_join_text,
        ),
        DocsBlock.Note(R.string.docs_start_language_note),
        DocsBlock.Paragraph(R.string.docs_start_code),
        DocsBlock.Paragraph(R.string.docs_start_server),
        DocsBlock.Paragraph(R.string.docs_start_leaving),
    )

    DocsPage.TABS -> listOf(
        DocsBlock.Paragraph(R.string.docs_tabs_intro),
        DocsBlock.Points(
            listOf(
                R.string.docs_tabs_today,
                R.string.docs_tabs_week,
                R.string.docs_tabs_homework,
            ),
        ),
        DocsBlock.Paragraph(R.string.docs_tabs_homework_filter),
        DocsBlock.Paragraph(R.string.docs_tabs_settings),
        DocsBlock.Note(R.string.docs_tabs_widget_note),
    )

    DocsPage.WIDGET -> listOf(
        DocsBlock.Paragraph(R.string.docs_widget_intro),
        DocsBlock.Paragraph(R.string.docs_widget_adding),
        DocsBlock.Points(
            listOf(
                R.string.docs_widget_offline,
                R.string.docs_widget_wakeups,
                R.string.docs_widget_timezone,
                R.string.docs_widget_homework,
            ),
        ),
        DocsBlock.Note(R.string.docs_widget_no_data),
    )

    DocsPage.ALERTS -> listOf(
        DocsBlock.Paragraph(R.string.docs_alerts_intro),
        DocsBlock.Points(
            listOf(
                R.string.docs_alerts_lesson,
                R.string.docs_alerts_morning,
                R.string.docs_alerts_homework,
                R.string.docs_alerts_changes,
            ),
        ),
        DocsBlock.Paragraph(R.string.docs_alerts_quiet),
        DocsBlock.Note(R.string.docs_alerts_permission),
        DocsBlock.Paragraph(R.string.docs_alerts_test),
    )

    DocsPage.LANGUAGE -> listOf(
        DocsBlock.Paragraph(R.string.docs_language_intro),
        DocsBlock.Note(R.string.docs_language_legacy_note),
        DocsBlock.Paragraph(R.string.docs_language_appearance),
        DocsBlock.Paragraph(R.string.docs_language_translation),
        DocsBlock.Note(R.string.docs_language_translation_note),
    )

    DocsPage.TELEGRAM -> listOf(
        DocsBlock.Paragraph(R.string.docs_telegram_intro),
        DocsBlock.Paragraph(R.string.docs_telegram_code),
        DocsBlock.Paragraph(R.string.docs_telegram_unlink),
        DocsBlock.Points(
            listOf(
                R.string.docs_telegram_role_viewer,
                R.string.docs_telegram_role_editor,
                R.string.docs_telegram_role_admin,
                R.string.docs_telegram_role_owner,
            ),
        ),
        DocsBlock.Note(R.string.docs_telegram_editing_note),
        DocsBlock.Paragraph(R.string.docs_telegram_commands_intro),
        DocsBlock.Points(
            listOf(
                R.string.docs_telegram_command_schedule,
                R.string.docs_telegram_command_homework,
                R.string.docs_telegram_command_tasks,
                R.string.docs_telegram_command_remind,
                R.string.docs_telegram_command_calendar,
                R.string.docs_telegram_command_help,
            ),
        ),
    )

    DocsPage.DIARY -> listOf(
        DocsBlock.Paragraph(R.string.docs_diary_intro),
        DocsBlock.Paragraph(R.string.docs_diary_sign_in),
        DocsBlock.Note(R.string.docs_diary_password_note),
        DocsBlock.Paragraph(R.string.docs_diary_inside),
        DocsBlock.Note(R.string.docs_diary_foreign_note),
    )

    DocsPage.ADMIN -> listOf(
        DocsBlock.Paragraph(R.string.docs_admin_intro),
        DocsBlock.Paragraph(R.string.docs_admin_screens_intro),
        DocsBlock.Points(
            listOf(
                R.string.docs_admin_screen_class,
                R.string.docs_admin_screen_subjects,
                R.string.docs_admin_screen_bells,
                R.string.docs_admin_screen_timetable,
                R.string.docs_admin_screen_devices,
                R.string.docs_admin_screen_log,
                R.string.docs_admin_screen_stats,
                R.string.docs_admin_screen_requests,
            ),
        ),
        DocsBlock.Note(R.string.docs_admin_security_note),
        DocsBlock.Paragraph(R.string.docs_admin_code),
    )
}

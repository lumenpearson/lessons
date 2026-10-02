package com.lumenpearson.lessons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ShellMode

/**
 * One page of the settings tree.
 *
 * The preferences used to be a single scroll of six labelled groups, which is
 * how Essentials' own settings screen started and is not where it ended up: it
 * splits them across pages you drop into, each with its own title in the pill at
 * the bottom and its own way back. Two dozen rows on one page means scrolling
 * past five things you did not come for, and the row you want is never where you
 * left it because the groups above it grow and shrink with their own switches.
 *
 * The enum is the single definition of the split. The root screen lists it, the
 * section screen renders one of it, and the shell titles the toolbar from it, so
 * a new section is one entry here and one branch in [SettingsSectionScreen].
 * Which of them the root offers is [listedOn], one rule for both homes.
 *
 * @param tone which accent slot the row's tile takes on the root page.
 */
enum class SettingsSection(
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val icon: ImageVector,
    val tone: Int,
) {
    APPEARANCE(
        R.string.settings_appearance,
        R.string.settings_appearance_summary,
        Icons.Rounded.Palette,
        4,
    ),
    FEEL(
        R.string.settings_feel,
        R.string.settings_feel_summary,
        Icons.Rounded.TouchApp,
        2,
    ),
    CONTENT(
        R.string.settings_content,
        R.string.settings_content_summary,
        Icons.Rounded.ViewAgenda,
        3,
    ),
    // There are six accent slots and nine rows on the landing page, so three
    // hues are used twice. Each pair is put as far apart as the list allows:
    // this one shares slot 5 with "О приложении", last of the nine.
    ALERTS(
        R.string.settings_alerts,
        R.string.settings_alerts_summary,
        Icons.Rounded.NotificationsActive,
        5,
    ),
    SYNC(
        R.string.settings_sync,
        R.string.settings_sync_summary,
        Icons.Rounded.CloudSync,
        0,
    ),
    ACCOUNT(
        R.string.settings_class,
        R.string.settings_class_summary,
        Icons.Rounded.School,
        1,
    ),
    /**
     * The diary: a second account, in a service this app does not own, that
     * most installs in a class will never have — and, on a phone in no class,
     * the only account there is.
     *
     * In a class it is a section rather than a fourth tab because a tab would
     * show a sign-in wall in the bottom bar of everybody without such an
     * account, for good — the toolbar never hides a destination. Next to
     * «Класс» because the two rows are the same kind of thing: which account
     * this phone is signed in to. The page it opens is not a list of
     * preferences, which is why [SettingsSectionScreen] hands it over whole: the
     * diary itself in a class, the account page on the diary home, where the
     * diary already is the home.
     */
    DIARY(
        R.string.diary_title,
        R.string.diary_section_summary,
        Icons.AutoMirrored.Rounded.MenuBook,
        4,
    ),
    UPDATES(
        R.string.settings_updates,
        R.string.settings_updates_summary,
        Icons.Rounded.SystemUpdate,
        2,
    ),
    ABOUT(
        R.string.settings_about,
        R.string.settings_about_summary,
        Icons.Rounded.Info,
        5,
    ),

    /**
     * The pages only an administrator or the owner of the class has.
     *
     * Listed by [listedOn] only once the server has said who this phone belongs
     * to — the same shape as [PERMISSIONS], for a different reason: that one
     * appears when something is wrong, this one when somebody is allowed.
     */
    ADMIN(
        R.string.settings_admin,
        R.string.settings_admin_summary,
        Icons.Rounded.AdminPanelSettings,
        1,
    ),

    /**
     * Reached from the notifications page, never from the root list.
     *
     * It is a page about a fault, so it exists only while there is one: a
     * permanent "Разрешения" row on the landing page would be one more thing to
     * read past on every visit, and would say nothing on the phones — most of
     * them — where everything is granted. [listedOn] is what keeps it off.
     */
    PERMISSIONS(
        R.string.permissions_title,
        R.string.permissions_banner_description,
        Icons.Rounded.Shield,
        5,
    ),

    /**
     * The developer mode (#237), listed once seven taps on the version have
     * found it. Last, under everything a reader came for, and a page of its own
     * like [DIARY]: its tools are lists that grow while it is open, and none of
     * them is a preference. Whether its tools open is the GitHub gate's
     * question, asked on the page — listing it grants nothing.
     */
    DEVELOPER(
        R.string.developer_section,
        R.string.developer_section_summary,
        Icons.Rounded.DeveloperMode,
        tone = 3,
    ),
    ;

    /**
     * Whether the landing page offers a row for this section, on a phone in
     * [mode] — [manager] being whether the server has said this phone may
     * manage its class.
     *
     * One rule for both homes, replacing a flag that could only say «never on
     * the root». On the diary home the rows that are about a class go: its
     * timetable and widget ([CONTENT]), its alerts ([ALERTS]) — planned from a
     * class timetable only, so a switch there would be a promise nothing keeps
     * — and the class account itself ([ACCOUNT]), whose `/me` and Telegram link
     * need a class token this phone does not have. What stays is what is about
     * the phone ([APPEARANCE], [FEEL], [UPDATES], [ABOUT]), the server address
     * every diary read goes through ([SYNC]), and the diary.
     *
     * [developer] is whether the developer section has been found; it is about
     * the phone, so it is listed on every home alike.
     */
    fun listedOn(mode: ShellMode, manager: Boolean, developer: Boolean = false): Boolean = when (this) {
        PERMISSIONS -> false
        ADMIN -> mode == ShellMode.CLASS && manager
        CONTENT, ALERTS, ACCOUNT -> mode != ShellMode.DIARY
        APPEARANCE, FEEL, SYNC, DIARY, UPDATES, ABOUT -> true
        DEVELOPER -> developer
    }

    companion object {
        /** `null` for anything this build does not have, including `null` itself. */
        fun fromName(name: String?): SettingsSection? = entries.firstOrNull { it.name == name }
    }
}

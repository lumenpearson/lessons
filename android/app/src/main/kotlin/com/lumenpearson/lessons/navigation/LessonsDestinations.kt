package com.lumenpearson.lessons.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumenpearson.lessons.R

/**
 * Every place the user can be.
 *
 * String routes rather than the 2.8 `@Serializable` type-safe API: none of these
 * destinations carries an argument, so the type-safe variant would buy nothing
 * but would drag the kotlinx-serialization plugin into a module that otherwise
 * needs no code generation at all. The sealed hierarchy still gives the one
 * thing that matters — no route string is ever spelled out at a call site.
 */
sealed class LessonsRoute(val path: String) {

    /** First run / signed-out. Deliberately outside the navigation bar. */
    data object Join : LessonsRoute("join")

    /** Home: what is happening right now. */
    data object Today : LessonsRoute("today")

    /** The week at a glance. */
    data object Week : LessonsRoute("week")

    /** Everything that has been set, grouped by due date. */
    data object Homework : LessonsRoute("homework")

    /** Preferences, class membership, about. */
    data object Settings : LessonsRoute("settings")
}

/**
 * The four destinations reachable from the navigation bar, in bar order.
 *
 * Modelled as an enum so the bar, the selected-state check and the back stack
 * all read from one list and cannot drift apart.
 *
 * @property route where the item navigates.
 * @property labelRes localized label; also used as the item's accessibility name.
 * @property icon bar icon.
 */
enum class TopLevelDestination(
    val route: LessonsRoute,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    TODAY(LessonsRoute.Today, R.string.nav_today, Icons.Rounded.Today),
    WEEK(LessonsRoute.Week, R.string.nav_week, Icons.Rounded.CalendarViewWeek),
    HOMEWORK(LessonsRoute.Homework, R.string.nav_homework, Icons.Rounded.MenuBook),
    SETTINGS(LessonsRoute.Settings, R.string.nav_settings, Icons.Rounded.Settings),
    ;

    companion object {
        /** `true` when [path] is one of the bar destinations, i.e. show the bar. */
        fun isTopLevel(path: String?): Boolean = entries.any { it.route.path == path }
    }
}

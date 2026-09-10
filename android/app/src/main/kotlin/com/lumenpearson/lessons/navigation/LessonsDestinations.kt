package com.lumenpearson.lessons.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.model.HomeTab

/**
 * What each tab looks like in the bar.
 *
 * Written as extensions rather than as fields of [HomeTab] because the enum
 * lives in `:core:model`, which has no Compose and no resources: the data layer
 * has to be able to store "which tab" without being able to draw one.
 */
@get:StringRes
val HomeTab.labelRes: Int
    get() = when (this) {
        HomeTab.TODAY -> R.string.nav_today
        HomeTab.WEEK -> R.string.nav_week
        HomeTab.HOMEWORK -> R.string.nav_homework
        HomeTab.SETTINGS -> R.string.nav_settings
    }

/** @see labelRes */
val HomeTab.icon: ImageVector
    get() = when (this) {
        HomeTab.TODAY -> Icons.Rounded.Today
        HomeTab.WEEK -> Icons.Rounded.CalendarViewWeek
        HomeTab.HOMEWORK -> Icons.Rounded.MenuBook
        HomeTab.SETTINGS -> Icons.Rounded.Settings
    }

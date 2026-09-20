package com.lumenpearson.lessons.ui.docs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyph a page carries in the toolbar, chosen by the id in its metadata.
 *
 * Each icon is the one the same subject already wears elsewhere in the app —
 * the diary's book, the notification bell, the badge of the management page —
 * because a reader who has seen the settings list has already learned them.
 *
 * A lookup rather than an enum, because the pages are no longer declared in
 * Kotlin: they arrive as markdown, from the repository, possibly from a version
 * of the guide newer than this build. A page this app has never heard of gets
 * the generic one and appears in the bar like any other — which is the whole
 * point of fetching the guide rather than shipping it.
 */
internal fun docsIcon(id: String): ImageVector = when (id.uppercase()) {
    "START" -> Icons.AutoMirrored.Rounded.Login
    "TABS" -> Icons.Rounded.ViewAgenda
    "WIDGET" -> Icons.Rounded.Widgets
    "ALERTS" -> Icons.Rounded.NotificationsActive
    "LANGUAGE" -> Icons.Rounded.Language
    "TELEGRAM" -> Icons.Rounded.Link
    "DIARY" -> Icons.AutoMirrored.Rounded.MenuBook
    "ADMIN" -> Icons.Rounded.AdminPanelSettings
    else -> Icons.Rounded.Article
}

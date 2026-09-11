package com.lumenpearson.lessons.core.model

/**
 * The one thing the widget can ask the app to do.
 *
 * Kept here, in the module with no Android in it, because both sides of the link
 * need the same two strings and neither should be the one that owns them: the
 * widget builds the intent, the activity reads it, and a typo in either is a tap
 * that silently opens the wrong screen.
 *
 * The date travels as ISO-8601 text rather than as an epoch day, so a stale
 * pending intent from a previous install is either parseable and correct or
 * obviously wrong, never off by an era.
 */
object DeepLink {

    /** Action of the intent the widget's day chips fire. */
    const val ACTION_OPEN_DAY: String = "com.lumenpearson.lessons.action.OPEN_DAY"

    /** Extra carrying the date, as `yyyy-MM-dd`. */
    const val EXTRA_DATE: String = "com.lumenpearson.lessons.extra.DATE"
}
